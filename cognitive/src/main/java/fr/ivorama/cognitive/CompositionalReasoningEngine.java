package fr.ivorama.cognitive;

import java.util.*;

/**
 * IVOC-REASON-003: finite ground Horn-style composition, with negative literals,
 * provenance, retraction and a persistent interrogative obligation in FCI/RDO.
 * Rules are supplied premises, not learned/empirically verified laws. No explosion
 * from inconsistencies. This component does NOT parse unrestricted French.
 */
public final class CompositionalReasoningEngine {
    public record Literal(String name, boolean positive) {
        public Literal {
            if(name == null || !name.matches("[a-z][a-z0-9_]{0,39}"))
                throw new IllegalArgumentException("Nom de proposition invalide");
        }
        public Formula formula() { return positive ? Formula.atom("c_"+name) : Formula.not(Formula.atom("c_"+name)); }
        public Literal opposite() { return new Literal(name,!positive); }
        public String toString() { return name+"="+(positive?"oui":"non"); }
    }
    public enum State { YES, NO, UNKNOWN, INCONSISTENT, RESOURCE_LIMIT }
    public record Result(String questionId, State state, String answer, String explanation,
                         List<String> proofSteps, List<String> indispensableSources,
                         List<String> missing, long version, boolean reopened, boolean proofChecked) {
        public Result {
            proofSteps=List.copyOf(proofSteps);
            indispensableSources=List.copyOf(indispensableSources);
            missing=List.copyOf(missing);
        }
    }
    private record Fact(String id, Literal literal, String source, String premise) { }
    private record Rule(String id, List<Literal> antecedents, Literal consequent, String source, String premise) { }
    private record Witness(String id, List<Literal> antecedents, boolean contrapositive) { }
    private final IntegratedQuestionCycle fci=new IntegratedQuestionCycle();
    private final Map<String,Fact> facts=new LinkedHashMap<>();
    private final Map<String,Rule> rules=new LinkedHashMap<>();
    private final Map<Literal,String> questions=new LinkedHashMap<>();
    private static final int MAX_ATOMS=12, MAX_RULES=48, MAX_FACTS=48;
    private record CounterfactualKey(String excludedSource, Literal goal) {}
    private final Map<CounterfactualKey,ModelExplorer.Analysis> counterfactualCache=new LinkedHashMap<>();
    private long cacheVersion=-1, counterfactualSolverCalls, counterfactualCacheHits;
    public record Diagnostics(long solverCalls,long cacheHits,int cachedChecks,long knowledgeVersion) {}
    public Diagnostics diagnostics() {
        refreshCache();
        return new Diagnostics(counterfactualSolverCalls,counterfactualCacheHits,counterfactualCache.size(),cacheVersion);
    }
    private void refreshCache() {
        if(cacheVersion!=version()) {counterfactualCache.clear();cacheVersion=version();}
    }
    private ModelExplorer.Analysis analyzeWithout(String source,Literal goal) {
        refreshCache();
        var key=new CounterfactualKey(source,goal);
        var cached=counterfactualCache.get(key);
        if(cached!=null) {counterfactualCacheHits++;return cached;}
        Fact f=facts.get(source);Rule r=rules.get(source);
        String excluded=f!=null?f.premise:r!=null?r.premise:null;
        if(excluded==null)throw new IllegalStateException("Source de preuve introuvable : "+source);
        Set<Formula> remaining=new LinkedHashSet<>();
        for(KnowledgeBase.Premise premise:fci.premises())
            if(premise.active()&&!premise.id().equals(excluded))remaining.add(premise.formula());
        var result=ModelExplorer.analyze(remaining,goal.formula(),ModelExplorer.Budget.small());
        counterfactualSolverCalls++;
        // Borné par 96 sources x 24 littéraux ; pas de cache d'états intermédiaires.
        counterfactualCache.put(key,result);
        return result;
    }


    public void fact(String id, Literal literal, String source) {
        validateId(id); Objects.requireNonNull(literal); validateSource(source);
        if(facts.size()==MAX_FACTS) throw new IllegalStateException("Limite de faits atteinte");
        availableId(id); checkAtoms(literal,List.of());
        String premise=fci.teach(literal.formula(),"FAIT DÉCLARÉ NON CERTIFIÉ : "+id+" / "+source);
        facts.put(id,new Fact(id,literal,source,premise));
    }
    public void rule(String id,List<Literal> antecedents,Literal consequent,String source) {
        validateId(id); Objects.requireNonNull(antecedents); Objects.requireNonNull(consequent);validateSource(source);
        availableId(id);
        if(rules.size()==MAX_RULES) throw new IllegalStateException("Limite de règles atteinte");
        if(antecedents.isEmpty() || antecedents.size()>4 || antecedents.stream().anyMatch(Objects::isNull) ||
                new LinkedHashSet<>(antecedents).size()!=antecedents.size())
            throw new IllegalArgumentException("La règle exige 1 à 4 conditions distinctes");
        checkAtoms(consequent,antecedents);
        Formula left=antecedents.get(0).formula();
        for(int i=1;i<antecedents.size();i++) left=Formula.and(left,antecedents.get(i).formula());
        String premise=fci.teach(Formula.implies(left,consequent.formula()),
                "RÈGLE DÉCLARÉE NON CERTIFIÉE : "+id+" / "+source);
        rules.put(id,new Rule(id,List.copyOf(antecedents),consequent,source,premise));
    }
    public void withdraw(String id) {
        validateId(id);
        Fact fact=facts.remove(id);
        if(fact!=null) { fci.retract(fact.premise);return; }
        Rule rule=rules.remove(id);
        if(rule!=null) {fci.retract(rule.premise);return;}
        throw new IllegalArgumentException("Source ou règle inconnue : "+id);
    }
    public Result ask(Literal goal) {
        Objects.requireNonNull(goal);checkAtoms(goal,List.of());
        String q=questions.computeIfAbsent(goal,g->fci.ask(g.formula()).id());
        return evaluate(goal,q);
    }
    public Result revisit(Literal goal) {
        String id=questions.get(goal);
        if(id==null) throw new IllegalArgumentException("Question pas encore posée");
        return evaluate(goal,id);
    }
    public int ruleCount() {return rules.size();}
    public int factCount() {return facts.size();}
    public long version() {return fci.knowledgeVersion();}
    private Result evaluate(Literal goal,String q) {
        var report=fci.question(q);
        // A conservative fixed point gives inspectable derivation chains.
        Map<Literal,Witness> derived=closure(Set.of());
        boolean both=derived.containsKey(goal) && derived.containsKey(goal.opposite());
        State state;
        if(report.status()==IntegratedQuestionCycle.Status.INCONSISTENT || both)
            state=State.INCONSISTENT;
        else if(report.status()==IntegratedQuestionCycle.Status.RESOURCE_LIMIT)
            state=State.RESOURCE_LIMIT;
        else if(report.answer().isPresent() && report.answer().get())
            state=State.YES;
        else if(report.answer().isPresent() && !report.answer().get())
            state=State.NO;
        else state=State.UNKNOWN;
        List<String> proof=new ArrayList<>(), sources=new ArrayList<>(), missing=new ArrayList<>();
        String detail;
        if(state==State.YES || state==State.NO) {
            Literal concluded=state==State.YES ? goal:goal.opposite();
            if(derived.containsKey(concluded)) {
                LinkedHashSet<String> referenced=new LinkedHashSet<>();
                explain(concluded,derived,new HashSet<>(),proof,referenced);
                // Test each premise against the FULL logical consequence, not just
                // the first chosen forward-chain witness (there may be alternatives).
                for(String id:referenced) {
                    var without=analyzeWithout(id,goal);
                    boolean still=without.status()==ModelExplorer.Status.PROVEN_TRUE && state==State.YES ||
                            without.status()==ModelExplorer.Status.PROVEN_FALSE && state==State.NO;
                    if(!still) sources.add(id);
                }
            } else {
                // Sound fallback for valid consequences requiring a combination of
                // clauses beyond simple chain/contraposition. Do not invent a proof path.
                proof.add("CONSÉQUENCE PAR MODÈLES (sans chaîne constructive retrouvée) : "+concluded);
            }
            detail="La conclusion découle conditionnellement des faits et règles déclarés; "
                    +"ce n'est pas une certification empirique.";
        } else if(state==State.UNKNOWN) {
            for(Rule r:rules.values()) if(r.consequent.equals(goal)) {
                var absent=r.antecedents.stream().filter(l->!derived.containsKey(l)).map(Object::toString).toList();
                if(!absent.isEmpty()) missing.add("règle "+r.id+" : manque "+absent);
            }
            detail="Données insuffisantes : aucune preuve ni réfutation déductible.";
        } else if(state==State.INCONSISTENT) {
            for(Literal lit:derived.keySet()) if(lit.positive && derived.containsKey(lit.opposite())) {
                missing.add("conflit : "+lit+" ET "+lit.opposite());
            }
            detail="Des prémisses impliquent une contradiction; aucune conclusion n'est certifiée.";
        } else detail="Limite de calcul FCI atteinte; conclusion suspendue.";
        String answer=switch(state) {
            case YES -> "OUI (sous les prémisses déclarées)";
            case NO -> "NON (sous les prémisses déclarées)";
            case UNKNOWN -> "INDÉTERMINÉ";
            case INCONSISTENT -> "CONTRADICTION — conclusion suspendue";
            case RESOURCE_LIMIT -> "LIMITE DE CALCUL — conclusion suspendue";
        };
        return new Result(q,state,answer,detail,proof,sources,missing,
                report.knowledgeVersion(),report.reopened(),report.proofChecked());
    }
    private Map<Literal,Witness> closure(Set<String> excluded) {
        Map<Literal,Witness> found=new LinkedHashMap<>();
        for(Fact f:facts.values()) if(!excluded.contains(f.id))
            found.putIfAbsent(f.literal,new Witness(f.id,List.of(),false));
        boolean change;
        do {
            change=false;
            for(Rule r:rules.values()) if(!excluded.contains(r.id) && !found.containsKey(r.consequent) &&
                    r.antecedents.stream().allMatch(found::containsKey)) {
                found.put(r.consequent,new Witness(r.id,r.antecedents,false));
                change=true;
            }
            // Valid classical contraposition: (A and B => C), not C, A entails not B.
            // No inference is made from the ABSENCE of C; a negative witness is required.
            for(Rule r:rules.values()) if(!excluded.contains(r.id)
                    && found.containsKey(r.consequent.opposite())) {
                for(int i=0;i<r.antecedents.size();i++) {
                    Literal absent=r.antecedents.get(i), conclusion=absent.opposite();
                    if(found.containsKey(conclusion)) continue;
                    List<Literal> supporting=new ArrayList<>();
                    supporting.add(r.consequent.opposite());
                    boolean othersAvailable=true;
                    for(int j=0;j<r.antecedents.size();j++) if(i!=j) {
                        Literal premise=r.antecedents.get(j);
                        if(!found.containsKey(premise)) {othersAvailable=false;break;}
                        supporting.add(premise);
                    }
                    if(othersAvailable) {
                        found.put(conclusion,new Witness(r.id,List.copyOf(supporting),true));
                        change=true;
                    }
                }
            }
        } while(change);
        return found;
    }
    private void explain(Literal lit,Map<Literal,Witness> found,Set<Literal> visited,
                         List<String> lines,Set<String> sources) {
        if(!visited.add(lit)) return;
        Witness w=found.get(lit);
        if(w==null) throw new IllegalStateException("Preuve absente : "+lit);
        for(Literal a:w.antecedents) explain(a,found,visited,lines,sources);
        sources.add(w.id);
        if(w.antecedents.isEmpty()) {
            Fact f=facts.get(w.id);
            lines.add("FAIT ["+w.id+"] "+lit+" ; source : "+f.source);
        } else {
            Rule r=rules.get(w.id);
            lines.add((w.contrapositive?"CONTRAPOSITION":"RÈGLE")+" ["+w.id+"] "
                    +w.antecedents+" => "+lit+" ; source : "+r.source);
        }
    }
    private void availableId(String id) {
        if(facts.containsKey(id)||rules.containsKey(id)) throw new IllegalArgumentException("ID déjà utilisé : "+id);
    }
    private void checkAtoms(Literal head,List<Literal> other) {
        Set<String> existing=new HashSet<>();
        facts.values().forEach(f->existing.add(f.literal.name));
        rules.values().forEach(r->{existing.add(r.consequent.name);r.antecedents.forEach(l->existing.add(l.name));});
        questions.keySet().forEach(q->existing.add(q.name));
        existing.add(head.name);other.forEach(l->existing.add(l.name));
        if(existing.size()>MAX_ATOMS) throw new IllegalStateException("Maximum de 12 propositions distinctes");
    }
    private static void validateId(String id) {
        if(id==null || !id.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Identifiant invalide");
    }
    private static void validateSource(String source) {
        if(source==null||source.isBlank()||source.length()>500)
            throw new IllegalArgumentException("Source obligatoire (maximum 500 caractères)");
    }
}
