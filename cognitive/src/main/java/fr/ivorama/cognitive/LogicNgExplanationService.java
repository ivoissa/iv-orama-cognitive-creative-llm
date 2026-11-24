package fr.ivorama.cognitive;

import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.logicng.formulas.FormulaFactory;
import org.logicng.solvers.MiniSat;
import org.logicng.solvers.sat.MiniSatConfig;
import org.logicng.propositions.StandardProposition;
import org.logicng.handlers.TimeoutSATHandler;
import org.logicng.datastructures.Tristate;
import static fr.ivorama.cognitive.SemanticExplanation.*;

/** Opt-in bounded explanation service. Fresh solver/factory per call; source ids are
 * proposition descriptions, never inferred from formula equality. proofGeneration
 * enables LogicNG's DRUP core extraction, NOT an IV-ORAMA syntactic certificate.
 * Calls, conversion visits, subsets and independent assignments have cumulative caps.
 * SAT timeout is cooperative. Clause insertion/DRUP extraction are not hard-interruptible.
 */
public final class LogicNgExplanationService implements SemanticExplanationProvider {
    public record Limits(int maxPremises,int maxNodes,int maxDepth,int maxSatCalls,
            long maxSubsetVisits,int maxSupports,long perSolveMillis,int maxOracleAtoms,
            long maxOracleAssignments,long totalMillis) {
        public Limits {
            if(maxPremises<0 || maxPremises>128 || maxNodes<1 || maxDepth<1 || maxDepth>128
                    || maxSatCalls<1 || maxSubsetVisits<0 || maxSupports<1 || perSolveMillis<1
                    || maxOracleAtoms<0 || maxOracleAtoms>20 || maxOracleAssignments<1
                    || totalMillis<1 || totalMillis>60_000)throw new IllegalArgumentException("Invalid explanation limits");
        }
        public static Limits small(){return new Limits(64,20_000,128,256,1024,16,100,12,1_000_000,2000);}
    }
    private final Limits limits;
    public LogicNgExplanationService(){this(Limits.small());}
    public LogicNgExplanationService(Limits limits){this.limits=Objects.requireNonNull(limits);}
    public Limits limits(){return limits;}
    private static final String QUERY="@negated_target";
    private static final class Stop extends RuntimeException {
        final Status status;Stop(Status status,String reason){super(reason);this.status=status;}
    }
    private final class Work {
        final long start=System.nanoTime();
        final FormulaFactory factory=new FormulaFactory();
        final List<Premise> input;
        final Map<String,org.logicng.formulas.Formula> converted=new LinkedHashMap<>();
        int nodes;long calls,assignments,subsets;String digest="";
        org.logicng.formulas.Formula convertedQuestion;
        Kind kind=Kind.NONE;ModelExplorer.Status semantic=ModelExplorer.Status.RESOURCE_LIMIT;
        Optional<Formula> target=Optional.empty();Optional<Core> raw=Optional.empty(),mus=Optional.empty();
        final List<Core> supports=new ArrayList<>();
        Work(Collection<Premise> premises){
            Objects.requireNonNull(premises);input=List.copyOf(premises);
            Set<String> ids=new HashSet<>();
            for(Premise p:input)if(!ids.add(p.id()))throw new IllegalArgumentException("Duplicate premise id: "+p.id());
        }
        void checkpoint(){if((System.nanoTime()-start)/1_000_000>=limits.totalMillis())throw new Stop(Status.RESOURCE_LIMIT,"Total cooperative time budget");}
        org.logicng.formulas.Formula convert(Formula f,int depth){
            checkpoint();if(++nodes>limits.maxNodes() || depth>limits.maxDepth())throw new Stop(Status.RESOURCE_LIMIT,"Conversion visit/depth budget");
            if(f instanceof Formula.Atom a)return factory.variable(a.name());
            if(f instanceof Formula.Bottom)return factory.falsum();
            if(f instanceof Formula.And a)return factory.and(convert(a.left(),depth+1),convert(a.right(),depth+1));
            var i=(Formula.Implies)f;return factory.implication(convert(i.premise(),depth+1),convert(i.conclusion(),depth+1));
        }
        void prepare(Formula question){
            if(input.size()>limits.maxPremises())throw new Stop(Status.RESOURCE_LIMIT,"Premise count budget");
            long metadata=0;
            for(Premise p:input){metadata+=(long)p.source().length()+p.excerpt().length();if(metadata>1_000_000)throw new Stop(Status.RESOURCE_LIMIT,"Metadata size budget");converted.put(p.id(),convert(p.formula(),0));}
            convertedQuestion=convert(question,0); // bound AST before hashing, evaluation or source formatting
            digest=contextDigest(input,question);checkpoint();
        }
        record Solve(Tristate status,List<String> ids){}
        Solve solve(List<Premise> ps,org.logicng.formulas.Formula fixed,boolean extract){
            checkpoint();if(calls>=limits.maxSatCalls())throw new Stop(Status.RESOURCE_LIMIT,"SAT call budget");calls++;
            var solver=MiniSat.miniSat(factory,MiniSatConfig.builder().proofGeneration(extract).build());
            for(Premise p:ps)solver.add(new StandardProposition(p.id(),converted.get(p.id())));
            if(fixed!=null)solver.add(new StandardProposition(QUERY,fixed));
            checkpoint();long remaining=Math.max(1,limits.totalMillis()-(System.nanoTime()-start)/1_000_000);
            var state=solver.sat(new TimeoutSATHandler(Math.min(remaining,limits.perSolveMillis())));
            checkpoint();if(state==Tristate.UNDEF)throw new Stop(Status.RESOURCE_LIMIT,"SAT cooperative timeout");
            List<String> ids=new ArrayList<>();
            if(extract && state==Tristate.FALSE){
                var core=solver.unsatCore(); // Actual LogicNG 2.6.2 API, not a guessed wrapper.
                checkpoint();
                for(var proposition:core.propositions()) {
                    if(!(proposition instanceof StandardProposition p))throw new IllegalStateException("Unknown core proposition type");
                    if(p.description().equals(QUERY))continue;
                    if(!converted.containsKey(p.description()) || ps.stream().noneMatch(x->x.id().equals(p.description())))
                        throw new IllegalStateException("Unmapped core proposition rejected");
                    ids.add(p.description());
                }
            }
            return new Solve(state,List.copyOf(ids));
        }
        boolean independentUnsat(List<Premise> ps,Formula fixed){
            checkpoint();List<Formula> formulas=new ArrayList<>(ps.stream().map(Premise::formula).toList());
            if(fixed!=null)formulas.add(fixed);
            Set<String> names=new TreeSet<>();formulas.forEach(f->names.addAll(Formula.atoms(f)));
            if(names.size()>limits.maxOracleAtoms())throw new Stop(Status.VERIFICATION_LIMIT,"Independent atom budget");
            long cost=1L<<names.size();
            if(cost>limits.maxOracleAssignments()-assignments)throw new Stop(Status.VERIFICATION_LIMIT,"Cumulative independent assignment budget");
            assignments+=cost;
            var analysis=ModelExplorer.analyzeFull(formulas,Formula.Bottom.INSTANCE,new ModelExplorer.Budget(limits.maxOracleAtoms(),cost));
            checkpoint();if(analysis.status()==ModelExplorer.Status.RESOURCE_LIMIT)throw new Stop(Status.VERIFICATION_LIMIT,"Independent model budget");
            return analysis.status()==ModelExplorer.Status.INCONSISTENT;
        }
        List<Premise> select(List<String> ids){Set<String> selected=new HashSet<>(ids);return input.stream().filter(p->selected.contains(p.id())).toList();}
        void setRaw(List<Premise> core,Formula fixed){
            raw=Optional.of(new Core(core,false,false));
            if(!independentUnsat(core,fixed))throw new IllegalStateException("Independent core verification failed");
            raw=Optional.of(new Core(core,true,false));
        }
        Core verifyMinimal(List<Premise> ps,Formula fixed) {
            if(!independentUnsat(ps,fixed))throw new IllegalStateException("Candidate is not independently UNSAT");
            for(int i=0;i<ps.size();i++) {
                var trial=new ArrayList<>(ps);trial.remove(i);
                if(independentUnsat(trial,fixed))throw new IllegalStateException("Nonminimal candidate rejected");
            }
            return new Core(ps,true,true);
        }
        Core shrink(List<Premise> seed,org.logicng.formulas.Formula fixed,Formula fixedOriginal) {
            List<Premise> current=new ArrayList<>(seed);
            for(Premise p:seed) {
                var trial=new ArrayList<>(current);trial.removeIf(x->x.id().equals(p.id()));
                if(solve(trial,fixed,false).status()==Tristate.FALSE)current=trial;
            }
            return verifyMinimal(current,fixedOriginal);
        }
        boolean containsKnownSupport(List<Premise> trial) {
            Set<String> ids=new HashSet<>();trial.forEach(p->ids.add(p.id()));
            return supports.stream().anyMatch(c->ids.containsAll(c.ids()));
        }
        void enumerateSupports(org.logicng.formulas.Formula fixed,Formula fixedOriginal) {
            if(supports.stream().anyMatch(c->c.premises().isEmpty()))return;
            int n=input.size();
            for(int k=0;k<=n;k++) {
                int[] indexes=new int[k];for(int i=0;i<k;i++)indexes[i]=i;
                boolean more=true;
                while(more) {
                    checkpoint();if(subsets>=limits.maxSubsetVisits())throw new Stop(Status.RESOURCE_LIMIT,"Subset visit budget; alternatives incomplete");subsets++;
                    List<Premise> trial=new ArrayList<>();for(int i:indexes)trial.add(input.get(i));
                    if(!containsKnownSupport(trial) && solve(trial,fixed,false).status()==Tristate.FALSE) {
                        Core support=verifyMinimal(trial,fixedOriginal);
                        if(supports.size()>=limits.maxSupports())throw new Stop(Status.RESOURCE_LIMIT,"Support count budget; alternatives incomplete");
                        supports.add(support);
                    }
                    if(k==0)more=false;
                    else {
                        int i=k-1;while(i>=0 && indexes[i]==n-k+i)i--;
                        if(i<0)more=false;
                        else {indexes[i]++;for(int j=i+1;j<k;j++)indexes[j]=indexes[j-1]+1;}
                    }
                }
            }
        }
        SemanticExplanation result(Status status,boolean complete,String reason){
            return new SemanticExplanation(kind,status,semantic,target,raw,mus,supports,complete,calls,assignments,subsets,digest,reason);
        }
    }
    /** Stage-one conflict extraction. MUS/support enrichment is a separate opt-in stage. */
    public SemanticExplanation conflict(Collection<Premise> premises){
        Work w=new Work(premises);
        try {
            w.prepare(Formula.Bottom.INSTANCE);
            var solved=w.solve(w.input,null,true);
            if(solved.status()==Tristate.TRUE){w.semantic=ModelExplorer.Status.PROVEN_FALSE;return w.result(Status.NOT_APPLICABLE,false,"Context is satisfiable; no UNSAT core");}
            w.kind=Kind.CONFLICT;w.semantic=ModelExplorer.Status.INCONSISTENT;
            w.setRaw(w.select(solved.ids()),null);
            return w.result(Status.COMPLETE,false,"Raw core only; no claim of minimality or complete conflict enumeration");
        } catch(Stop stop){return w.result(stop.status,false,stop.getMessage());}
          catch(IllegalStateException ex){return w.result(Status.FAILED,false,ex.getMessage());}
    }
    /** One MUS, not a cardinality-minimum core and not an enumeration of all conflicts. */
    public SemanticExplanation minimalConflict(Collection<Premise> premises) {
        return explain(premises,Formula.Bottom.INSTANCE,false);
    }
    /** All minimal implication supports only if every bounded subset is visited.
     * Query assumptions are fixed solver propositions, never declared sources.
     */
    public SemanticExplanation explain(Collection<Premise> premises,Formula question) {
        return explain(premises,Objects.requireNonNull(question),true);
    }
    private SemanticExplanation explain(Collection<Premise> premises,Formula question,boolean implication) {
        Work w=new Work(premises);
        try {
            w.prepare(question);var base=w.solve(w.input,null,true);
            if(base.status()==Tristate.FALSE) {
                w.kind=Kind.CONFLICT;w.semantic=ModelExplorer.Status.INCONSISTENT;
                w.setRaw(w.select(base.ids()),null);
                w.mus=Optional.of(w.shrink(w.raw.orElseThrow().premises(),null,null));
                return w.result(Status.COMPLETE,false,"One independently verified MUS; other conflicts not enumerated");
            }
            if(!implication){w.semantic=ModelExplorer.Status.PROVEN_FALSE;return w.result(Status.NOT_APPLICABLE,false,"Satisfiable context; no conflict");}
            Formula fixedOriginal=Formula.not(question);var fixed=w.factory.not(w.convertedQuestion);
            var selected=w.solve(w.input,fixed,true);
            if(selected.status()==Tristate.FALSE) {w.semantic=ModelExplorer.Status.PROVEN_TRUE;w.target=Optional.of(question);}
            else {
                fixedOriginal=question;fixed=w.convertedQuestion;selected=w.solve(w.input,fixed,true);
                if(selected.status()==Tristate.TRUE){w.semantic=ModelExplorer.Status.UNDETERMINED;return w.result(Status.NOT_APPLICABLE,false,"Both answers have models; no implication support");}
                w.semantic=ModelExplorer.Status.PROVEN_FALSE;w.target=Optional.of(Formula.not(question));
            }
            w.kind=Kind.IMPLICATION;
            // Reject vacuous implication: the WHOLE declared context must be independently consistent.
            if(w.independentUnsat(w.input,null))throw new IllegalStateException("Context consistency disagreement");
            w.setRaw(w.select(selected.ids()),fixedOriginal);
            Core primary=w.shrink(w.raw.orElseThrow().premises(),fixed,fixedOriginal);
            w.supports.add(primary);
            w.enumerateSupports(fixed,fixedOriginal);
            return w.result(Status.COMPLETE,true,"All inclusion-minimal supports enumerated within the declared budgets; no syntactic certificate");
        } catch(Stop stop){return w.result(stop.status,false,stop.getMessage());}
          catch(IllegalStateException ex){return w.result(Status.FAILED,false,ex.getMessage());}
    }
    /** Bounded freshness check includes ids, formulas, source texts, excerpts and the original query. */
    public boolean matches(SemanticExplanation result,Collection<Premise> premises,Formula question) {
        if(result.contextDigest().isEmpty())return false;
        Work w=new Work(premises);
        try{w.prepare(question);return result.contextDigest().equals(w.digest);}
        catch(Stop stop){return false;}
    }
    private static String contextDigest(Collection<Premise> premises,Formula question){
        try {
            var md=MessageDigest.getInstance("SHA-256");
            for(Premise p:premises)for(String field:List.of(p.id(),p.formula().toString(),p.source(),p.excerpt()))update(md,field);
            update(md,question.toString());return HexFormat.of().formatHex(md.digest());
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void update(MessageDigest md,String field){byte[] bytes=field.getBytes(StandardCharsets.UTF_8);md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());md.update(bytes);}
}
