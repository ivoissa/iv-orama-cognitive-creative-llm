package fr.ivorama.cognitive.agent;

import org.junit.jupiter.api.Test;

class AuditRegressionTest {
    @Test void windowsAnchor() throws Exception { AuditRegressionCases.windowsAnchor(); }
    @Test void incompleteEvidence() { AuditRegressionCases.incompleteEvidence(); }
    @Test void multilineSecrets() throws Exception { AuditRegressionCases.multilineSecrets(); }
    @Test void readBounds() throws Exception { AuditRegressionCases.readBounds(); }
    @Test void actionBudget() throws Exception { AuditRegressionCases.actionBudget(); }
}
