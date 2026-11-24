package fr.ivorama.cognitive;

import java.util.*;

/** Deterministic checks; truth-table evaluator deliberately independent of Formula.evaluate. */
public final class P21RegressionTestMain {
    private static void ok(boolean b,String message) {if(!b)throw new AssertionError(message);}
    private static Formula a(String n) {return Formula.atom(n);}
    private static Formula imp(Formula p,Formula q) {return Formula.implies(p,q);}
    static List<Formula> components() {
        List<Formula> ps=new ArrayList<>();
        for(int i=0;i<3;i++)ps.add(imp(Formula.and(a("a"+i),a("b"+i)),a("c"+i)));
        return ps;
    }
    static void cumulativeBoundary() {
        var ps=components();var b=new ModelExplorer.Budget(3,8);
        var exact=ModelExplorer.analyze(ps,a("a0"),b,24);
        ok(exact.status()==ModelExplorer.Status.UNDETERMINED,"exact cumulative allowance");
        ok(exact.explanation().contains("24 local assignments"),"sum is 24, not 8");
        var capped=ModelExplorer.analyze(ps,a("a0"),b,23);
        ok(capped.status()==ModelExplorer.Status.RESOURCE_LIMIT,"aggregate exhaustion explicit");
        ok(capped.explanation().contains("visited=16"),"stop BEFORE unaffordable component");
        ok(capped.possibleWorlds().isEmpty() && capped.trueWitness().isEmpty()
            && capped.falseWitness().isEmpty() && !capped.completeWorlds(),"no partial global witness");
        ok(ModelExplorer.analyze(ps,a("a0"),b).equals(
            ModelExplorer.analyze(ps,a("a0"),b,Long.MAX_VALUE)),"legacy contract preserved");
    }
    static void remoteContradiction() {
        var ps=components();ps.add(a("a2"));ps.add(a("b2"));ps.add(Formula.not(a("c2")));
        var b=new ModelExplorer.Budget(3,8);
        ok(ModelExplorer.analyze(ps,a("a0"),b,16).status()==ModelExplorer.Status.RESOURCE_LIMIT,
            "unvisited contradiction must not be silently ignored");
        ok(ModelExplorer.analyze(ps,a("a0"),b,24).status()==ModelExplorer.Status.INCONSISTENT,
            "remote inconsistency found with sufficient allowance");
    }
    static void fullAndExtras() {
        Formula q=Formula.and(a("a"),a("b"));var b=new ModelExplorer.Budget(2,4);
        ok(ModelExplorer.analyzeFull(List.of(),q,b,3).status()==ModelExplorer.Status.RESOURCE_LIMIT,"full count limited");
        ok(ModelExplorer.analyzeFull(List.of(),q,b,4).possibleWorlds().size()==4,"exact full count");
        var extra=List.of(a("b"),a("c"));var small=new ModelExplorer.Budget(1,2);
        var yes=ModelExplorer.analyze(List.of(),a("a"),extra,small,6);
        ok(yes.status()==ModelExplorer.Status.UNDETERMINED,"extras remain free, not asserted premises");
        ok(yes.trueWitness().orElseThrow().values().size()==3,"complete witness includes extras");
        ok(ModelExplorer.analyze(List.of(),a("a"),extra,small,5).status()==ModelExplorer.Status.RESOURCE_LIMIT,"extras consume work");
        ok(ModelExplorer.analyzeFull(List.of(),imp(a("a"),a("a")),List.of(a("b")),b,3).status()==
            ModelExplorer.Status.RESOURCE_LIMIT,"full extra variables consume allowance");
        ok(ModelExplorer.analyzeFull(List.of(),Formula.Bottom.INSTANCE,new ModelExplorer.Budget(0,1),1)
            .status()==ModelExplorer.Status.PROVEN_FALSE,"empty valuation counts once");
        for(long invalid:new long[]{0,-1}) {
            boolean rejected=false;try{ModelExplorer.analyze(List.of(),q,b,invalid);}catch(IllegalArgumentException e){rejected=true;}
            ok(rejected,"reject non-positive total");
            rejected=false;try{ModelExplorer.analyzeFull(List.of(),q,b,invalid);}catch(IllegalArgumentException e){rejected=true;}
            ok(rejected,"reject non-positive full total");
        }
    }
    static void nonEnumeratingTwoSat() {
        List<Formula> ps=new ArrayList<>();ps.add(a("n0"));
        for(int i=0;i<24;i++)ps.add(imp(a("n"+i),a("n"+(i+1))));
        var result=ModelExplorer.analyze(ps,a("n24"),ModelExplorer.Budget.small(),1);
        ok(result.status()==ModelExplorer.Status.PROVEN_TRUE && result.explanation().contains("2-SAT"),
            "assignment ceiling is not a fictitious SAT-operation/time budget");
    }
    static void conjunctiveAntecedent() {
        var p=a("p");var q=a("q");var r=a("r");
        var ps=List.of(p,q,imp(Formula.and(p,q),r));
        ok(ProofSearch.findWithBudget(ps,r,128,100000).proof().isEmpty(),"baseline fragment does not synthesize this antecedent");
        checkProof(ps,r,ProofSearch.findIndexedWithBudget(ps,r,128,100000));
        var nested=List.of(p,q,a("s"),imp(Formula.and(p,Formula.and(q,a("s"))),r));
        checkProof(nested,r,ProofSearch.findIndexedWithBudget(nested,r,128,100000));
        ok(ProofSearch.findIndexedWithBudget(List.of(p,ps.get(2)),r,128,100000).proof().isEmpty(),"missing conjunct never invented");
    }
    static void indexingAndBudgets() {
        List<Formula> chain=new ArrayList<>();int n=80;
        for(int i=n-1;i>=0;i--)chain.add(imp(a("n"+i),a("n"+(i+1))));chain.add(a("n0"));
        var goal=a("n80");var ref=ProofSearch.findWithBudget(chain,goal,512,1000000);
        var indexed=ProofSearch.findIndexedWithBudget(chain,goal,512,1000000);
        checkProof(chain,goal,ref);checkProof(chain,goal,indexed);
        ok(indexed.operations()<ref.operations(),"reverse chain operation reduction");
        var exhausted=ProofSearch.findIndexedWithBudget(chain,goal,512,1);
        ok(exhausted.status()==ProofSearch.SearchStatus.RESOURCE_LIMIT && exhausted.proof().isEmpty()
            && exhausted.operations()==1,"exact work ceiling, fail closed");
        ok(ProofSearch.findIndexedWithBudget(chain,goal,2,1000000).status()==ProofSearch.SearchStatus.RESOURCE_LIMIT,
            "capacity cannot silently drop premises");
        for(int capacity:new int[]{1,2,3,4}) {
            var ps=List.of(a("x"),imp(a("x"),a("y")));
            var out=ProofSearch.findIndexedWithBudget(ps,a("missing"),capacity,10000);
            ok(out.status()!=ProofSearch.SearchStatus.FOUND,"unknown target never certified");
        }
    }
    static void contextIsolation() {
        var p=a("p");var q=a("q");
        checkProof(List.of(),imp(p,p),ProofSearch.findIndexedWithBudget(List.of(),imp(p,p),128,100000));
        Formula poisoned=Formula.and(imp(p,p),p);
        ok(ProofSearch.findIndexedWithBudget(List.of(),poisoned,128,100000).proof().isEmpty(),"temporary assumption does not leak to sibling");
        var ps=List.of(p,imp(p,q));var proof=ProofSearch.findIndexedWithBudget(ps,q,128,100000);
        checkProof(ps,q,proof);
        ok(ProofSearch.findIndexedWithBudget(List.of(imp(p,q)),q,128,100000).proof().isEmpty(),"no cache after retraction");
        ok(!ProofChecker.verify(proof.proof().orElseThrow(),List.of(imp(p,q))).valid(),"old term rejected after retraction");
        checkProof(ps,q,ProofSearch.findIndexedWithBudget(ps,q,128,100000));
        var cycle=List.of(imp(p,q),imp(q,p));
        ok(ProofSearch.findIndexedWithBudget(cycle,q,128,100000).proof().isEmpty(),"cyclic implications are not evidence");
    }
    static void oracleAndDifferential() {
        var rng=new Random(20261101);var atoms=List.of(a("a"),a("b"),a("c"),a("d"));
        for(int i=0;i<500;i++) {
            List<Formula> ps=new ArrayList<>();for(int j=0;j<rng.nextInt(7);j++)ps.add(random(rng,atoms,2));
            Formula goal=random(rng,atoms,2);
            var expected=oracle(ps,goal,atoms);var b=new ModelExplorer.Budget(4,16);
            ok(ModelExplorer.analyze(ps,goal,b,16).status()==expected,"independent truth oracle "+i);
            var ref=ProofSearch.findWithBudget(ps,goal,512,1000000);
            var idx=ProofSearch.findIndexedWithBudget(ps,goal,512,1000000);
            if(ref.proof().isPresent() && ProofChecker.verify(ref.proof().orElseThrow(),ps).valid())
                ok(idx.proof().isPresent(),"no lost reference proof "+i);
            if(idx.proof().isPresent()) {
                checkProof(ps,goal,idx);
                ok(expected==ModelExplorer.Status.PROVEN_TRUE || expected==ModelExplorer.Status.INCONSISTENT,
                    "independent truth table refutes unsound proof "+i);
            }
        }
    }
    static void alternativeSources() {
        var cycle=new IntegratedQuestionCycle();var p=a("p");var q=a("q");
        var first=cycle.teach(p,"A");var second=cycle.teach(p,"B");cycle.teach(imp(p,q),"rule");
        checkProof(active(cycle),q,ProofSearch.findIndexedWithBudget(active(cycle),q,128,100000));
        cycle.retract(first);
        checkProof(active(cycle),q,ProofSearch.findIndexedWithBudget(active(cycle),q,128,100000));
        cycle.retract(second);
        ok(ProofSearch.findIndexedWithBudget(active(cycle),q,128,100000).proof().isEmpty(),"last source retraction removes support");
    }
    static void factoredOracle() {
        var r=new Random(2121);var atoms=List.of(a("a"),a("b"),a("c"),a("d"),a("e"),a("f"));
        for(int i=0;i<200;i++) {
            List<Formula> ps=new ArrayList<>();
            for(int k=0;k<6;k+=2) {
                var x=atoms.get(k);var y=atoms.get(k+1);
                ps.add(imp(x,y));if(r.nextBoolean())ps.add(x);if(r.nextBoolean())ps.add(Formula.not(y));
            }
            var q=atoms.get(r.nextInt(6));var actual=ModelExplorer.analyze(ps,q,new ModelExplorer.Budget(2,4),12);
            ok(actual.status()==oracle(ps,q,atoms),"factored independent oracle "+i);
            for(var w:List.of(actual.trueWitness(),actual.falseWitness()))if(w.isPresent()) {
                var values=new HashMap<Formula,Boolean>();for(var atom:atoms)values.put(atom,w.get().values().get(((Formula.Atom)atom).name()));
                for(var p:ps)ok(eval(p,values),"independent witness validation");
            }
        }
    }
    private static List<Formula> active(IntegratedQuestionCycle c) {
        return c.premises().stream().filter(KnowledgeBase.Premise::active).map(KnowledgeBase.Premise::formula).toList();
    }
    private static void checkProof(List<Formula> ps,Formula goal,ProofSearch.SearchOutcome out) {
        ok(out.status()==ProofSearch.SearchStatus.FOUND,"expected proof: "+goal);
        var checked=ProofChecker.verify(out.proof().orElseThrow(),ps);
        ok(checked.valid() && goal.equals(checked.conclusion()),"independent exact-goal check");
    }
    private static Formula random(Random r,List<Formula> as,int d) {
        int k=r.nextInt(d==0?5:8);
        if(k<4)return as.get(k);if(k==4)return Formula.Bottom.INSTANCE;
        return k==5?Formula.and(random(r,as,d-1),random(r,as,d-1)):imp(random(r,as,d-1),random(r,as,d-1));
    }
    private static boolean eval(Formula f,Map<Formula,Boolean> as) {
        if(f instanceof Formula.Atom)return as.get(f);
        if(f instanceof Formula.Bottom)return false;
        if(f instanceof Formula.And x)return eval(x.left(),as)&eval(x.right(),as);
        var x=(Formula.Implies)f;return !eval(x.premise(),as)|eval(x.conclusion(),as);
    }
    private static ModelExplorer.Status oracle(List<Formula> ps,Formula q,List<Formula> atoms) {
        boolean yes=false,no=false;
        for(int m=0;m<(1<<atoms.size());m++) {
            Map<Formula,Boolean> as=new HashMap<>();for(int i=0;i<atoms.size();i++)as.put(atoms.get(i),(m&(1<<i))!=0);
            boolean holds=true;for(Formula p:ps)holds &=eval(p,as);
            if(holds){if(eval(q,as))yes=true;else no=true;}
        }
        return yes?(no?ModelExplorer.Status.UNDETERMINED:ModelExplorer.Status.PROVEN_TRUE):
            no?ModelExplorer.Status.PROVEN_FALSE:ModelExplorer.Status.INCONSISTENT;
    }
    public static void main(String[] args) {
        cumulativeBoundary();remoteContradiction();fullAndExtras();nonEnumeratingTwoSat();
        conjunctiveAntecedent();indexingAndBudgets();contextIsolation();oracleAndDifferential();alternativeSources();factoredOracle();
        System.out.println("P2.1 PASS: 10 groups, 700 independent truth-table/differential cases");
    }
}
