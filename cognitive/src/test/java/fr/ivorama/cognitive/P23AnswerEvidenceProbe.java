package fr.ivorama.cognitive;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline P2.3c integration test on actual production FCI/ProofChecker/ProofSearch. */
public final class P23AnswerEvidenceProbe {
    private static int n;
    private static void yes(boolean condition, String reason) {
        n++;
        if (!condition) throw new AssertionError("P2.3c: " + reason);
    }
    private static Formula a(String name) { return Formula.atom(name); }
    private static SemanticExplanationProvider fake(AtomicInteger calls) {
        return new SemanticExplanationProvider() {
            @Override public SemanticExplanation explain(
                    Collection<SemanticExplanation.Premise> premises, Formula goal) {
                calls.incrementAndGet();
                throw new IllegalStateException("synthetic SAT failure");
            }
            @Override public boolean matches(SemanticExplanation e,
                    Collection<SemanticExplanation.Premise> premises, Formula goal) {
                throw new AssertionError("Unexpected call after simulated failure");
            }
        };
    }
    public static void main(String[] args) {
        n=0;
        var calls = new AtomicInteger();
        var options = new P23ProofRouting.Options(40, 10_000, 2, 32);
        var cycle = new IntegratedQuestionCycle();
        cycle.teach(a("a"),"first-declared-source");  // P1
        cycle.teach(a("a"),"alternative-declared-source");  // P2
        cycle.teach(Formula.implies(a("a"),a("b")),"rule-source"); // P3
        var answer = cycle.ask(a("b"));
        yes(answer.answer().equals(Optional.of(true)), "baseline answer yes");
        var journal=cycle.journal();
        var evidence=P23AnswerEvidence.inspect(cycle,answer.id(),fake(calls),options);
        yes(calls.get()==1,"one failed optional provider call");
        yes(answer.equals(evidence.original()),"original report preserved");
        yes(cycle.question(answer.id()).equals(answer),"FCI decision unchanged");
        yes(cycle.journal().equals(journal),"no new question acts");
        yes(evidence.optionalRoute().isPresent(),"route included for decided question");
        yes(evidence.optionalRoute().orElseThrow().proofChecked(),"indexed fallback verifies actual proof");
        yes(evidence.optionalRoute().orElseThrow().fallbackUsed(),"fallback after provider failure");
        yes(evidence.routedProofDependencies().size()==2,"two necessary proof formulas");
        var aGroup=evidence.routedProofDependencies().stream().filter(g->g.formula().equals(a("a")))
                .findFirst().orElseThrow();
        yes(aGroup.alternativeSources().size()==2,"alternative sources both retained");
        yes(aGroup.alternativeSources().get(0).premiseId().equals("P1"),"first id retained");
        yes(aGroup.alternativeSources().get(1).premiseId().equals("P2"),"second id retained");
        yes(aGroup.alternativeSources().get(0).declaredSource().equals("first-declared-source"),
                "first source retained");
        yes(!evidence.additionalCheckedProof(),"not claim novelty if baseline already has a proof");
        yes(evidence.isCurrent(cycle),"fresh receipt");
        yes(P23AnswerEvidence.summary(evidence).contains("non vérifiées empiriquement"),
                "semantic trust never inferred");
        yes(!P23AnswerEvidence.summary(evidence).contains("first-declared-source"),
                "untrusted source text not embedded in summary");
        var forged=new P23AnswerEvidence.DependencyGroup(a("a"),
                List.of(new P23AnswerEvidence.Attribution("P1","invented-source")));
        boolean rejects=false;
        try { new P23AnswerEvidence.Evidence(answer, evidence.optionalRoute(),
                    List.of(forged),false,"bad"); }
        catch (IllegalArgumentException ex) {rejects=true;}
        yes(rejects,"forged/incomplete source lineage rejected");

        cycle.retract("P1");
        yes(!evidence.isCurrent(cycle),"retraction invalidates evidence even with alternate source");
        var updated=P23AnswerEvidence.inspect(cycle,answer.id(),fake(calls),options);
        var updatedA=updated.routedProofDependencies().stream()
                .filter(g->g.formula().equals(a("a"))).findFirst().orElseThrow();
        yes(updatedA.alternativeSources().size()==1,"one remaining active source");
        yes(updatedA.alternativeSources().get(0).premiseId().equals("P2"),"remaining source is P2");
        yes(updated.isCurrent(cycle),"recomputed evidence fresh");
        cycle.retract("P2");
        var reopened=cycle.question(answer.id());
        yes(reopened.answer().isEmpty(),"FCI reopened after last source removed");
        int beforeCalls=calls.get();
        var unresolved=P23AnswerEvidence.inspect(cycle,answer.id(),fake(calls),options);
        yes(unresolved.optionalRoute().isEmpty(),"no route for unresolved answer");
        yes(calls.get()==beforeCalls,"provider not called for unresolved answer");
        yes(P23AnswerEvidence.summary(unresolved).contains("non résolue"),"unknown status rendered correctly");

        var direct=new IntegratedQuestionCycle();
        direct.teach(a("x"),"direct-claim");
        var d=direct.ask(a("x"));
        var dEvidence=P23AnswerEvidence.inspect(direct,d.id(),fake(new AtomicInteger()),options);
        yes(dEvidence.optionalRoute().orElseThrow().proofChecked(),"direct assumption checked");
        yes(!dEvidence.additionalCheckedProof(),"direct assertion not elevated to independent proof");
        yes(P23AnswerEvidence.summary(dEvidence).contains("sans élévation"),"direct basis not inflated");
        var neg=new IntegratedQuestionCycle();
        neg.teach(Formula.not(a("x")),"negative-declared");
        var no=neg.ask(a("x"));
        var negEvidence=P23AnswerEvidence.inspect(neg,no.id(),fake(new AtomicInteger()),options);
        yes(no.answer().equals(Optional.of(false)),"negative baseline established");
        yes(negEvidence.optionalRoute().orElseThrow().goal().equals(Formula.not(a("x"))),
                "negated goal is correct");
        yes(P23AnswerEvidence.summary(negEvidence).contains("NON"),"negative answer rendered");

        var inconsistent=new IntegratedQuestionCycle();
        inconsistent.teach(a("p"),"a");inconsistent.teach(Formula.not(a("p")),"not-a");
        var bad=inconsistent.ask(a("p"));
        var noRoute=P23AnswerEvidence.inspect(inconsistent,bad.id(),fake(new AtomicInteger()),options);
        yes(noRoute.optionalRoute().isEmpty(),"inconsistency cannot produce proof receipt");
        yes(noRoute.original().status()==IntegratedQuestionCycle.Status.INCONSISTENT,"inconsistent status conserved");

        System.out.println("P23AnswerEvidenceProbe PASS: " + n + " assertions");
    }
}
