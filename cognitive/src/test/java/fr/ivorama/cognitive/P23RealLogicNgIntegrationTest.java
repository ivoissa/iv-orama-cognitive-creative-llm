package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Maven gate only: instantiates the REAL LogicNG 2.6.2 dependency. Not a mock. */
class P23RealLogicNgIntegrationTest {
    private static Formula a(String s) { return Formula.atom(s); }

    @Test void realSolverGuidanceIsVerifiedAndDoesNotMutateFci() {
        var cycle=new IntegratedQuestionCycle();
        cycle.teach(a("p"),"source-P");
        cycle.teach(Formula.implies(a("p"),a("q")),"source-implication");
        cycle.teach(Formula.implies(a("r"),a("r")),"noise-source");
        var before=cycle.ask(a("q"));
        var journal=cycle.journal();
        var metrics=P23LogicNgExperiment.measure(cycle,before.id(),
                new P23ProofRouting.Options(128,50_000,3,16),
                new LogicNgExplanationService.Limits(16,10_000,32,128,100,8,1000,8,100_000,8000));
        assertTrue(metrics.receipt().isPresent());
        var receipt=metrics.receipt().orElseThrow();
        assertTrue(receipt.proofChecked(),receipt.toString());
        assertEquals(P23ProofRouting.Route.GUIDED,receipt.route(),receipt.toString());
        assertEquals(java.util.Set.of(a("p"),Formula.implies(a("p"),a("q"))),receipt.proofDependencies());
        assertEquals(java.util.Set.of("P1","P2"),java.util.Set.copyOf(receipt.selectedSupportIds()));
        assertEquals(1,metrics.explainCalls());
        assertEquals(1,metrics.matchesCalls());
        assertTrue(metrics.totalWallNanos()>=0);
        assertTrue(metrics.providerWallNanos()>=0);
        assertEquals(journal,cycle.journal());
        assertEquals(before,cycle.question(before.id()));
        assertTrue(cycle.isProofRoutingReceiptCurrent(before.id(),receipt));
        cycle.retract("P1");
        assertFalse(cycle.isProofRoutingReceiptCurrent(before.id(),receipt));
    }

    @Test void boundedSolverDoesNotCreateAnUnverifiedCertificate() {
        var cycle=new IntegratedQuestionCycle();
        cycle.teach(a("p"),"source-P");
        cycle.teach(Formula.implies(a("p"),a("q")),"source-implication");
        var baseline=cycle.ask(a("q"));
        var before=cycle.question(baseline.id());
        var metrics=P23LogicNgExperiment.measure(cycle,baseline.id(),
                new P23ProofRouting.Options(64,100,2,16),
                new LogicNgExplanationService.Limits(16,1,4,2,0,1,1,2,4,1));
        var receipt=metrics.receipt().orElseThrow();
        assertNotEquals(P23ProofRouting.Route.GUIDED,receipt.route());
        assertTrue(receipt.selectedSupportIds().isEmpty() || receipt.proofChecked());
        assertEquals(before,cycle.question(baseline.id()));
        assertEquals(baseline.answer(),cycle.question(baseline.id()).answer());
    }
}
