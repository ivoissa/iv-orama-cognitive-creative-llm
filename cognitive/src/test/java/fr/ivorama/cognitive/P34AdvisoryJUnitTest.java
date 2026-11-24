package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Maven gate; does not depend on a provider, a SAT solver or a generative model. */
class P34AdvisoryJUnitTest {
    private static final ModelExplorer.Budget BUDGET=ModelExplorer.Budget.small();
    @Test void sourceMinimalContradictionNeverRewrites() {
        var r=P31CandidateAnswerAudit.inspect("any-model", "La porte A est ouverte. Le document B est valide.",
                "La porte A n'est pas ouverte.",BUDGET);
        var advice=P32SuggestedCorrections.propose(r,BUDGET);
        assertTrue(advice.complete());
        assertFalse(advice.permitsAutomaticReplacement());
        assertEquals(P32SuggestedCorrections.Action.CHALLENGE,advice.suggestions().getFirst().action());
        assertEquals(List.of("La porte A est ouverte"),advice.suggestions().getFirst().referenceExcerpts());
    }
    @Test void untranslatedDraftKeepsPartialDiagnosticsWithoutWholeCertification() {
        var r=P31CandidateAnswerAudit.inspect("any-model","La porte A est ouverte.",
                "La porte A n'est pas ouverte. Cela semble évident.",BUDGET);
        assertEquals(P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,r.status());
        assertFalse(r.isFullySupported());
        assertEquals(P31CandidateAnswerAudit.Status.CONTRADICTED,r.claims().getFirst().status());
        var advice=P32SuggestedCorrections.propose(r,BUDGET);
        assertFalse(advice.complete());
        assertEquals(P32SuggestedCorrections.Action.UNRESOLVED, advice.suggestions().getFirst().action());
    }
    @Test void incompleteReferenceProhibitsAllSourceClaims() {
        var r=P31CandidateAnswerAudit.inspect("any-model","La porte A est ouverte. Cela reste à confirmer.",
                "La porte A est ouverte.",BUDGET);
        assertEquals(P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,r.status());
        assertTrue(r.claims().isEmpty());
    }
}
