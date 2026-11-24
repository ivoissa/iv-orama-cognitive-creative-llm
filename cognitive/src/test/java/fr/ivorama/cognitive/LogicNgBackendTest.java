package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogicNgBackendTest {
    static final ModelExplorer.Budget B=ModelExplorer.Budget.small();
    static final Formula A=Formula.atom("a"), C=Formula.atom("c");
    static Formula random(Random r,int depth) {
        if(depth==0) return r.nextInt(6)==5 ? Formula.Bottom.INSTANCE : Formula.atom("v"+r.nextInt(5));
        return switch(r.nextInt(5)) {
            case 0 -> Formula.and(random(r,depth-1),random(r,depth-1));
            case 1 -> Formula.implies(random(r,depth-1),random(r,depth-1));
            case 2 -> Formula.not(random(r,depth-1));
            case 3 -> Formula.iff(random(r,depth-1),random(r,depth-1));
            default -> random(r,0);
        };
    }
    // Independent AST interpreter for the oracle (does not call Formula.evaluate).
    static boolean eval(Formula f,Map<String,Boolean> w) {
        if(f instanceof Formula.Atom a)return w.get(a.name());
        if(f instanceof Formula.Bottom)return false;
        if(f instanceof Formula.And a)return eval(a.left(),w) & eval(a.right(),w);
        var i=(Formula.Implies)f;return !eval(i.premise(),w) | eval(i.conclusion(),w);
    }
    // Independent iterative truth table; does not call either backend for classification.
    static ModelExplorer.Status oracle(List<Formula> ps,Formula q) {
        SortedSet<String> ns=new TreeSet<>(Formula.atoms(q));
        ps.forEach(p->ns.addAll(Formula.atoms(p))); var names=new ArrayList<>(ns);
        boolean yes=false,no=false;
        for(int mask=0;mask<(1<<names.size());mask++) {
            Map<String,Boolean> w=new HashMap<>();
            for(int i=0;i<names.size();i++)w.put(names.get(i),(mask&(1<<i))!=0);
            if(ps.stream().allMatch(p->eval(p,w))) { if(eval(q,w))yes=true;else no=true; }
        }
        return !yes&&!no?ModelExplorer.Status.INCONSISTENT:yes&&no?ModelExplorer.Status.UNDETERMINED:
                yes?ModelExplorer.Status.PROVEN_TRUE:ModelExplorer.Status.PROVEN_FALSE;
    }
    static void witnesses(List<Formula> ps,Formula q,ModelExplorer.Analysis result) {
        for(var pair:List.of(Map.entry(result.trueWitness(),true),Map.entry(result.falseWitness(),false))) {
            pair.getKey().ifPresent(w->{
                ps.forEach(p->assertTrue(w.satisfies(p)));assertEquals(pair.getValue(),w.satisfies(q));
                Set<String> ns=new TreeSet<>(Formula.atoms(q));ps.forEach(p->ns.addAll(Formula.atoms(p)));
                assertEquals(ns,w.values().keySet());
            });
        }
    }
    @Test void thousandSeededDifferentialsAndTruthTableOracles() {
        var r=new Random(0x71203);var sat=new LogicNgSatBackend();
        for(int i=0;i<1000;i++) {
            var ps=new ArrayList<Formula>();for(int j=0,n=r.nextInt(5);j<n;j++)ps.add(random(r,3));
            var q=random(r,3);var actual=sat.analyze(ps,q,B);
            assertEquals(oracle(ps,q),actual.status(),"case "+i);
            assertEquals(ModelExplorer.analyze(ps,q,B).status(),actual.status(),"case "+i);
            witnesses(ps,q,actual);assertFalse(actual.completeWorlds());assertTrue(actual.possibleWorlds().isEmpty());
        }
    }
    @Test void constantsAndSimplifiedAwayAtomsAndContradictionElsewhere() {
        var sat=new LogicNgSatBackend();
        for(var ps:List.of(List.<Formula>of(),List.<Formula>of(Formula.Bottom.INSTANCE),
                List.of(A,Formula.not(A)),List.of(Formula.implies(A,A)),List.of(A,A))) {
            for(var q:List.of(A,C,Formula.Bottom.INSTANCE,Formula.not(Formula.Bottom.INSTANCE),Formula.implies(C,C))) {
                var a=sat.analyze(ps,q,B);assertEquals(oracle(ps,q),a.status());witnesses(ps,q,a);
            }
        }
    }
    @Test void separateLimitsAreExplicitAndDoNotLeakState() {
        assertThrows(IllegalArgumentException.class,()->new LogicNgSatBackend(0,10,100));
        assertThrows(IllegalArgumentException.class,()->new LogicNgSatBackend(10,257,100));
        assertThrows(IllegalArgumentException.class,()->new LogicNgSatBackend(10,10,0));
        var tiny=new LogicNgSatBackend(2,10,100);
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,tiny.analyze(List.of(Formula.and(A,C)),A,B).status());
        Formula deep=A;for(int i=0;i<5000;i++)deep=Formula.not(deep);
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,new LogicNgSatBackend().analyze(List.of(),deep,B).status());
        assertEquals(ModelExplorer.Status.PROVEN_TRUE,tiny.analyze(List.of(A),A,B).status());
        assertEquals(ModelExplorer.Status.UNDETERMINED,tiny.analyze(List.of(),A,B).status());
        assertThrows(NullPointerException.class,()->tiny.analyze(List.of(),null,B));
    }
    @Test void largerNonTwoSatContextDoesNotClaimEnumerableWorldsOrCheckedProof() {
        List<Formula> ps=new ArrayList<>();Formula all=Formula.atom("x0");ps.add(all);
        for(int i=1;i<24;i++){var a=Formula.atom("x"+i);ps.add(a);all=Formula.and(all,a);}
        // Non-binary Horn clause connects 25 variables and defeats reference enumeration.
        ps.add(Formula.implies(all,Formula.atom("target")));
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,ModelExplorer.analyze(ps,Formula.atom("target"),B).status());
        var sat=new LogicNgSatBackend().analyze(ps,Formula.atom("target"),B);
        assertEquals(ModelExplorer.Status.PROVEN_TRUE,sat.status());assertFalse(sat.completeWorlds());
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,new CheckedReasoningBackend(new LogicNgSatBackend()).analyze(ps,Formula.atom("target"),B).status());
    }
    @Test void integratedCycleProofRetractionExplanationAndRdoStayEquivalent() {
        var reference=new IntegratedQuestionCycle();
        var candidate=new IntegratedQuestionCycle(B,new LogicNgSatBackend());
        for(var cycle:List.of(reference,candidate)) {
            cycle.teach(A,"admitted");String rule=cycle.teach(Formula.implies(A,C),"rule");
            var answer=cycle.ask(C);assertEquals(IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,answer.status());
            assertTrue(answer.proofChecked());var cert=answer.certificate().orElseThrow();
            assertTrue(ProofChecker.verify(cert.formalProof().orElseThrow(),Set.of(A,Formula.implies(A,C))).valid());
            assertFalse(cert.hasPremiseFreeFormalCertificate());
            assertEquals(IntegratedQuestionCycle.ExplanationStatus.PROVEN_CONDITIONALLY,cycle.askExplanation("why",C).status());
            cycle.retract(rule);assertTrue(cycle.question(answer.id()).reopened());
            assertEquals(IntegratedQuestionCycle.Status.OPEN,cycle.question(answer.id()).status());
            cycle.teach(Formula.not(A),"contradiction");assertEquals(IntegratedQuestionCycle.Status.INCONSISTENT,cycle.ask(C).status());
        }
        assertEquals(reference.questionActCount(),candidate.questionActCount());
    }
    @Test void rdoRequestsObservationAndDoesNotInventIt() {
        for(ReasoningBackend backend:List.of(new ModelExplorerBackend(),new LogicNgSatBackend())) {
            var cycle=new IntegratedQuestionCycle(B,backend);
            cycle.registerObservable(A,1);
            var report=cycle.ask(A);
            assertEquals(IntegratedQuestionCycle.Status.NEED_INFORMATION,report.status());
            assertEquals(Optional.of(A),report.requestedObservation());assertTrue(report.answer().isEmpty());
            var answered=cycle.answerObservation(report.id(),true,"fixture user declaration");
            assertEquals(Optional.of(true),answered.answer());
            assertNotEquals(IntegratedQuestionCycle.Status.RESTORED_FORMALLY,answered.status());
        }
    }
    @Test void disagreeingOrThrowingBackendCannotCloseObligation() {
        ReasoningBackend liar=new ReasoningBackend(){public String id(){return "liar";}
            public ModelExplorer.Analysis analyze(Collection<Formula> p,Formula q,ModelExplorer.Budget b){return ModelExplorer.analyze(List.of(q),q,b);}};
        var cycle=new IntegratedQuestionCycle(B,liar);var a=cycle.ask(A);
        assertEquals(IntegratedQuestionCycle.Status.RESOURCE_LIMIT,a.status());assertFalse(a.proofChecked());assertTrue(a.answer().isEmpty());
        ReasoningBackend broken=new ReasoningBackend(){public String id(){return "broken";}
            public ModelExplorer.Analysis analyze(Collection<Formula> p,Formula q,ModelExplorer.Budget b){throw new IllegalStateException("fixture");}};
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,new CheckedReasoningBackend(broken).analyze(List.of(),A,B).status());
        assertFalse(ProofChecker.verify(new Proof.Assumption(A),List.of()).valid());
    }
}
