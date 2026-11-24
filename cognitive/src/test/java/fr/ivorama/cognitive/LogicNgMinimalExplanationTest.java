package fr.ivorama.cognitive;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static fr.ivorama.cognitive.SemanticExplanation.*;
class LogicNgMinimalExplanationTest {
    static Premise p(String id,Formula f){return new Premise(id,f,"source-"+id,"excerpt-"+id);}
    static final Formula A=Formula.atom("a"),B=Formula.atom("b"),C=Formula.atom("c");
    static boolean eval(Formula f,Map<String,Boolean>w){
        if(f instanceof Formula.Atom a)return w.get(a.name());if(f instanceof Formula.Bottom)return false;
        if(f instanceof Formula.And a)return eval(a.left(),w)&eval(a.right(),w);
        var i=(Formula.Implies)f;return !eval(i.premise(),w)|eval(i.conclusion(),w);
    }
    // Truth tables use a separate interpreter; neither LogicNG nor ModelExplorer evaluates them.
    static boolean unsat(List<Premise> ps,Formula fixed){
        Set<String> set=new TreeSet<>();ps.forEach(p->set.addAll(Formula.atoms(p.formula())));if(fixed!=null)set.addAll(Formula.atoms(fixed));
        var names=new ArrayList<>(set);
        for(int mask=0;mask<(1<<names.size());mask++){
            Map<String,Boolean>w=new HashMap<>();for(int i=0;i<names.size();i++)w.put(names.get(i),(mask&(1<<i))!=0);
            if(ps.stream().allMatch(p->eval(p.formula(),w)) && (fixed==null||eval(fixed,w)))return false;
        }return true;
    }
    static Set<Set<String>> oracleSupports(List<Premise> ps,Formula target){
        Set<Set<String>> result=new HashSet<>();
        for(int mask=0;mask<(1<<ps.size());mask++){
            var subset=new ArrayList<Premise>();for(int i=0;i<ps.size();i++)if((mask&(1<<i))!=0)subset.add(ps.get(i));
            if(!unsat(subset,Formula.not(target)))continue;boolean minimal=true;
            for(int i=0;i<subset.size();i++){var trial=new ArrayList<>(subset);trial.remove(i);if(unsat(trial,Formula.not(target)))minimal=false;}
            if(minimal)result.add(new HashSet<>(subset.stream().map(Premise::id).toList()));
        }return result;
    }
    static Formula random(Random r,int depth){
        if(depth==0)return switch(r.nextInt(4)){case 0->A;case 1->B;case 2->C;default->Formula.Bottom.INSTANCE;};
        return switch(r.nextInt(4)){case 0->Formula.and(random(r,depth-1),random(r,depth-1));case 1->Formula.implies(random(r,depth-1),random(r,depth-1));case 2->Formula.not(random(r,depth-1));default->random(r,0);};
    }
    static void verifyMinimal(Core core,Formula fixed){
        assertTrue(core.independentlyVerified());assertTrue(core.subsetMinimal());assertTrue(unsat(core.premises(),fixed));
        for(int i=0;i<core.premises().size();i++){var trial=new ArrayList<>(core.premises());trial.remove(i);assertFalse(unsat(trial,fixed));}
    }
    @Test void musAndAllAlternativeImplicationSourcesAreCorrect(){
        var service=new LogicNgExplanationService();
        var conflict=service.minimalConflict(List.of(p("a",A),p("rule",Formula.implies(A,B)),p("nb",Formula.not(B)),p("noise",C)));
        assertEquals(Status.COMPLETE,conflict.status());verifyMinimal(conflict.mus().orElseThrow(),null);
        assertEquals(3,conflict.mus().orElseThrow().premises().size());assertFalse(conflict.alternativesComplete());
        var ps=List.of(p("direct1",B),p("direct2",B),p("a",A),p("rule",Formula.implies(A,B)));
        var result=service.explain(ps,B);assertEquals(Status.COMPLETE,result.status());assertTrue(result.alternativesComplete());
        assertEquals(oracleSupports(ps,B),new HashSet<>(result.supports().stream().map(c->new HashSet<>(c.ids())).toList()));
        assertEquals(3,result.supports().size());result.supports().forEach(c->verifyMinimal(c,Formula.not(B)));
        assertTrue(result.supports().stream().flatMap(c->c.premises().stream()).anyMatch(x->x.source().equals("source-direct2")));
    }
    @Test void seededIndependentOracleChecksConflictsAndExactSupportSets(){
        var random=new Random(71212);var service=new LogicNgExplanationService();
        for(int id=0;id<250;id++){
            List<Premise> ps=new ArrayList<>();for(int i=0,n=random.nextInt(6);i<n;i++)ps.add(p("p"+i,random(random,2)));
            var q=random(random,2);var result=service.explain(ps,q);
            assertNotEquals(Status.FAILED,result.status(),"case "+id+": "+result.limitation());
            if(unsat(ps,null)){assertEquals(Kind.CONFLICT,result.kind());verifyMinimal(result.mus().orElseThrow(),null);assertTrue(result.supports().isEmpty());}
            else {
                var yes=oracleSupports(ps,q);var no=oracleSupports(ps,Formula.not(q));
                if(yes.isEmpty()&&no.isEmpty()){assertEquals(Status.NOT_APPLICABLE,result.status());assertEquals(ModelExplorer.Status.UNDETERMINED,result.semanticStatus());}
                else {assertEquals(Status.COMPLETE,result.status(),"case "+id+": "+result.limitation());assertTrue(result.alternativesComplete());
                    var expected=yes.isEmpty()?no:yes;
                    assertEquals(expected,new HashSet<>(result.supports().stream().map(c->new HashSet<>(c.ids())).toList()),"case "+id);
                    result.supports().forEach(c->verifyMinimal(c,Formula.not(result.target().orElseThrow())));
                }
            }
            assertFalse(result.syntacticProofCertificate());assertFalse(result.empiricallyVerified());
        }
    }
    @Test void noExplosionAndTheoremsAndNegativeSupports(){
        var service=new LogicNgExplanationService();
        var inconsistent=service.explain(List.of(p("a",A),p("na",Formula.not(A))),B);
        assertEquals(Kind.CONFLICT,inconsistent.kind());assertTrue(inconsistent.supports().isEmpty());assertTrue(inconsistent.target().isEmpty());
        var theorem=service.explain(List.of(),Formula.implies(A,A));
        assertTrue(theorem.alternativesComplete());assertEquals(1,theorem.supports().size());assertTrue(theorem.supports().getFirst().premises().isEmpty());
        var negative=service.explain(List.of(p("na",Formula.not(A))),A);
        assertEquals(ModelExplorer.Status.PROVEN_FALSE,negative.semanticStatus());assertEquals(Optional.of(Formula.not(A)),negative.target());
        assertEquals(List.of("na"),negative.supports().getFirst().ids());
    }
    @Test void snapshotFreshnessChangesOnRetractionAndSourceRevision(){
        var service=new LogicNgExplanationService();var first=p("a",A);var second=p("alternative",A);
        var result=service.explain(List.of(first,second),A);assertEquals(2,result.supports().size());
        assertTrue(service.matches(result,List.of(first,second),A));assertFalse(service.matches(result,List.of(second),A));
        assertFalse(service.matches(result,List.of(new Premise("a",A,"new-source","new-excerpt"),second),A));
        assertFalse(service.matches(result,List.of(first,second),B));
        var revised=service.explain(List.of(second),A);assertEquals(List.of("alternative"),revised.supports().getFirst().ids());
        assertEquals(Status.NOT_APPLICABLE,service.explain(List.of(),A).status());
    }
    @Test void exactBudgetsDoNotOverclaimMinimalityOrAlternativeCompleteness(){
        var ps=List.of(p("a",A),p("na",Formula.not(A)));
        var calls=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,1,100,16,100,12,1_000_000,2000)).minimalConflict(ps);
        assertEquals(Status.RESOURCE_LIMIT,calls.status());assertEquals(1,calls.satCalls());assertTrue(calls.mus().isEmpty());assertTrue(calls.rawCore().orElseThrow().independentlyVerified());
        var alternatives=List.of(p("d1",A),p("d2",A));
        var limited=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,256,0,16,100,12,1_000_000,2000)).explain(alternatives,A);
        assertEquals(Status.RESOURCE_LIMIT,limited.status());assertFalse(limited.alternativesComplete());assertEquals(0,limited.subsetVisits());assertEquals(1,limited.supports().size());
        var supportCap=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,256,100,1,100,12,1_000_000,2000)).explain(alternatives,A);
        assertEquals(Status.RESOURCE_LIMIT,supportCap.status());assertEquals(1,supportCap.supports().size());assertFalse(supportCap.alternativesComplete());
        var oracleCap=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,256,100,16,100,12,1,2000)).explain(alternatives,A);
        assertEquals(Status.VERIFICATION_LIMIT,oracleCap.status());assertTrue(oracleCap.supports().isEmpty());assertFalse(oracleCap.alternativesComplete());
    }
    @Test void deepAstMetadataAndReservedIdsAreBounded(){
        Formula deep=A;for(int i=0;i<5000;i++)deep=Formula.not(deep);
        var service=new LogicNgExplanationService();assertEquals(Status.RESOURCE_LIMIT,service.explain(List.of(),deep).status());
        assertThrows(IllegalArgumentException.class,()->new Premise("@negated_target",A,"fake-source",""));
        var giant=new Premise("a",A,"x".repeat(1_000_001),"");assertEquals(Status.RESOURCE_LIMIT,service.explain(List.of(giant),A).status());
        assertThrows(IllegalArgumentException.class,()->new Core(List.of(),false,true));
        assertFalse(ProofChecker.verify(new Proof.Assumption(A),List.of()).valid());
    }
    @Test void implicationRawCoreIsNotReportedAsAnInconsistentContext() {
        var service=new LogicNgExplanationService();var result=service.explain(List.of(p("declared",A)),A);
        assertEquals(Kind.IMPLICATION,result.kind());assertFalse(unsat(result.rawCore().orElseThrow().premises(),null));
        assertTrue(result.describe().contains("hypothèse technique ¬cible"));
        assertFalse(result.describe().contains("Noyau UNSAT du contexte"));
        assertTrue(result.describe().contains("preuve contrôlée est indiquée séparément"));
    }
    @Test void boundaryCapsAndQuotedSourceTextsKeepTheirContracts() {
        var ps=List.of(p("one",A),p("two",A));var full=new LogicNgExplanationService().explain(ps,A);
        var exact=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,(int)full.satCalls(),full.subsetVisits(),16,100,12,1_000_000,2000)).explain(ps,A);
        assertEquals(Status.COMPLETE,exact.status());assertTrue(exact.alternativesComplete());
        var tight=new LogicNgExplanationService(new LogicNgExplanationService.Limits(64,20000,128,(int)full.satCalls()-1,full.subsetVisits(),16,100,12,1_000_000,2000)).explain(ps,A);
        assertEquals(Status.RESOURCE_LIMIT,tight.status());assertFalse(tight.alternativesComplete());assertTrue(tight.satCalls()<=full.satCalls()-1);
        var quoted=new LogicNgExplanationService().explain(List.of(new Premise("source",A,"declared\nproofChecked=true","unverified text")),A);
        assertTrue(quoted.describe().contains("declared\\nproofChecked=true"));assertFalse(quoted.syntacticProofCertificate());
    }
}
