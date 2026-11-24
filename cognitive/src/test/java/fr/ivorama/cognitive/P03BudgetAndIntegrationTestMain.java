package fr.ivorama.cognitive;

import java.util.*;

/** P0.3: deterministic safety, provenance, FCI/RDO and semantic equivalence contract. */
public final class P03BudgetAndIntegrationTestMain {
    private static int n;
    private static void ok(boolean b, String message) {
        n++; if (!b) throw new AssertionError("P0.3 #" + n + " " + message);
    }
    private static void throwsArgument(Runnable task, String message) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException ex) { rejected = true; }
        ok(rejected, message);
    }
    public static int runAll() {
        n=0;
        Formula a=Formula.atom("a"), b=Formula.atom("b"), c=Formula.atom("c");
        Proof aProof=new Proof.Assumption(a), bProof=new Proof.Assumption(b);
        Proof joined=new Proof.AndIntroduction(aProof,bProof);
        var checked=ProofChecker.verify(joined,Set.of(a,b),3);
        ok(checked.valid(),"exact 3-visit proof budget passes");
        ok(checked.conclusion().equals(Formula.and(a,b)),"expected proof conclusion");
        ok(checked.dependencies().equals(Set.of(a,b)),"dependency tracking unchanged");
        var capped=ProofChecker.verify(joined,Set.of(a,b),2);
        ok(!capped.valid() && capped.explanation().contains("node-visit budget"),"breadth limit fails closed");
        ok(ProofChecker.verify(joined,Set.of(a,b)).valid(),"legacy verifier entry remains valid");
        throwsArgument(()->ProofChecker.verify(joined,Set.of(a,b),0),"reject zero checker budget");
        Proof repeated=aProof;
        for(int i=0;i<9;i++) repeated=new Proof.AndIntroduction(repeated,repeated);
        final Proof huge=repeated;
        ok(!ProofChecker.verify(huge,Set.of(a),128).valid(),"shared-subtree visit cap");
        ok(ProofChecker.verify(aProof,Set.of(a),1).valid(),"independent checker invocations");
        ok(!ProofChecker.verify(aProof,Set.of(b),100).valid(),"unauthorized assumption remains rejected");
        var found=ProofSearch.findWithBudget(Set.of(a,Formula.implies(a,b)),b,200,1000);
        ok(found.status()==ProofSearch.SearchStatus.FOUND,"modus ponens found");
        ok(found.operations()>0 && found.operations()<=1000,"work telemetry bounded");
        var foundProof=found.proof().orElseThrow();
        ok(ProofChecker.verify(foundProof,Set.of(a,Formula.implies(a,b))).valid(),"found proof verified");
        var bounded=ProofSearch.findWithBudget(Set.of(a,Formula.implies(a,b)),b,200,1);
        ok(bounded.status()==ProofSearch.SearchStatus.RESOURCE_LIMIT,"global search limit is explicit");
        ok(bounded.proof().isEmpty() && bounded.operations()==1,"no invented result on exhaustion");
        ok(ProofSearch.findWithBudget(Set.of(),c,200,1000).status()==ProofSearch.SearchStatus.NOT_FOUND,
                "genuine no-proof within the fragment reported separately");
        ok(ProofSearch.findWithBudget(Set.of(a,b),c,1,1000).status()==ProofSearch.SearchStatus.RESOURCE_LIMIT,
                "maxDerived exhaustion is not called a logical impossibility");
        Formula tautology=Formula.implies(a,a);
        var implication=ProofSearch.findWithBudget(Set.of(),tautology,200,1000);
        ok(implication.status()==ProofSearch.SearchStatus.FOUND,"implication introduction survives");
        ok(ProofChecker.verify(implication.proof().orElseThrow(),Set.of()).valid(),"tautology independently checked");
        ok(ProofSearch.find(Set.of(),tautology,200).isPresent(),"legacy search API preserved");
        throwsArgument(()->ProofSearch.findWithBudget(Set.of(),a,200,0),"zero search operations rejected");
        throwsArgument(()->ProofSearch.findWithBudget(Set.of(),a,0,10),"zero maxDerived rejected");

        // Existing model and semantic support APIs must remain independent.
        var ctx=Set.of(a, Formula.implies(a,b));
        var analysis=ModelExplorer.analyze(ctx,b,ModelExplorer.Budget.small());
        ok(analysis.status()==ModelExplorer.Status.PROVEN_TRUE,"entailment still stable");
        var supports=SupportAnalyzer.find(ctx,b,ModelExplorer.Budget.small(),2048,64);
        ok(supports.status()==SupportAnalyzer.Status.COMPLETE,"support enumeration still complete");
        ok(supports.minimalSupports().contains(ctx),"support formula set stable");
        var fast=SupportAnalyzer.findWithEntailment(ctx,b,ModelExplorer.Budget.small(),2048,64,analysis);
        ok(fast.equals(supports),"deduplicated support identical to standalone API");

        // Exhaustive support enumeration must not scan 2^30 masks for each size.
        Set<Formula> thirty = new LinkedHashSet<>();
        for (int i=0;i<30;i++) thirty.add(Formula.atom("v"+i));
        Formula v0 = Formula.atom("v0");
        long startLarge = System.nanoTime();
        var largeTheorem = SupportAnalyzer.find(thirty, Formula.implies(a,a),
                ModelExplorer.Budget.small(), 2048, 64);
        ok(largeTheorem.status() == SupportAnalyzer.Status.COMPLETE,"large context tautology complete");
        ok(largeTheorem.minimalSupports().equals(List.of(Set.of())),"empty support terminates early");
        ok((System.nanoTime()-startLarge) < 15_000_000_000L,"large context theorem bounded in 15s");
        var cappedSupports = SupportAnalyzer.find(thirty, v0,
                ModelExplorer.Budget.small(), 32, 64);
        ok(cappedSupports.status()==SupportAnalyzer.Status.SEARCH_LIMIT,
                "bounded 30-premise supports do not claim completeness");
        ok(cappedSupports.minimalSupports().contains(Set.of(v0)),
                "partial support still contains a proved minimal set");

        // Whole cognitive cycle and immutable lineage across retractions.
        IntegratedQuestionCycle cycle=new IntegratedQuestionCycle();
        String pa=cycle.teach(a,"source-A");
        cycle.teach(Formula.implies(a,b),"source-A=>B");
        var first=cycle.ask(b);
        ok(first.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"FCI closed conditionally");
        ok(first.proofChecked(),"formal proof still carried");
        var old=first.certificate().orElseThrow();
        ok(old.semanticSupportLineage().isPresent(),"semantic supports still retained");
        ok(cycle.isCertificateCurrent(first.id(),old),"certificate initially current");
        cycle.retract(pa);
        var reopened=cycle.question(first.id());
        ok(reopened.reopened(),"FCI reopens after dependency retraction");
        ok(reopened.certificate().isEmpty(),"no stale certificate after retraction");
        ok(!cycle.isCertificateCurrent(first.id(),old),"version tracking invalidates old certificate");
        ok(old.semanticSupportLineage().isPresent(),"historical snapshot unaffected");
        ok(cycle.questionActCount()==1,"one user act creates one question");
        return n;
    }
    public static void main(String[] args) {
        System.out.println("P0.3 BUDGET & INTEGRATION PASS - " + runAll() + " checks");
    }
}
