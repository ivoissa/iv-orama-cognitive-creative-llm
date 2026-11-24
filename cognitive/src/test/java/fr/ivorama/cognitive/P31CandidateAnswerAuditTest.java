package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;

class P31CandidateAnswerAuditTest {
    @Test void deterministicLogicAndLanguageGates() {
        P31CandidateAnswerAuditProbe.main(new String[0]);
    }
    @Test void modelAgnosticFixtures() throws Exception {
        P31ModelAgnosticFixtureBench.main(new String[0]);
    }
}
