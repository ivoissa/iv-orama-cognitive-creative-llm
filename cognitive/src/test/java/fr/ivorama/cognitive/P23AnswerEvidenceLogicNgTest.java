package fr.ivorama.cognitive;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CI-only: uses the real LogicNG 2.6.2 runtime, NEVER a synthetic provider. */
class P23AnswerEvidenceLogicNgTest {
    @Test void realLogicNgExplanationProducesAttributableProofWithoutFciMutation() {
        var cycle = new IntegratedQuestionCycle();
        cycle.teach(Formula.atom("a"), "origin-a");
        cycle.teach(Formula.implies(Formula.atom("a"), Formula.atom("b")), "origin-rule");
        var original = cycle.ask(Formula.atom("b"));
        var journal = cycle.journal();
        var service = new LogicNgExplanationService(new LogicNgExplanationService.Limits(
                16,10_000,32,128,100,8,1_000,8,100_000,8_000));
        var evidence = P23AnswerEvidence.inspect(cycle, original.id(), service,
                new P23ProofRouting.Options(128,50_000,3,16));
        assertEquals(original, evidence.original());
        assertEquals(journal, cycle.journal());
        assertTrue(evidence.optionalRoute().isPresent());
        assertTrue(evidence.optionalRoute().orElseThrow().proofChecked());
        assertEquals(Set.of(Formula.atom("a"),
                Formula.implies(Formula.atom("a"), Formula.atom("b"))),
                evidence.optionalRoute().orElseThrow().proofDependencies());
        assertEquals(Set.of("P1","P2"), evidence.routedProofDependencies().stream()
                .flatMap(g -> g.alternativeSources().stream())
                .map(P23AnswerEvidence.Attribution::premiseId)
                .collect(java.util.stream.Collectors.toSet()));
        assertFalse(evidence.additionalCheckedProof(), "already proved by the baseline");
        assertTrue(evidence.isCurrent(cycle));
        cycle.retract("P1");
        assertFalse(evidence.isCurrent(cycle));
    }
}
