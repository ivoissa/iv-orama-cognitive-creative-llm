package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;

/** A Maven-visible wrapper around the real-core, no-dependency integration probe. */
class P23AnswerEvidenceIntegrationTest {
    @Test void evidenceDoesNotAlterFciOrInflateProofTrust() {
        P23AnswerEvidenceProbe.main(new String[0]);
    }
}
