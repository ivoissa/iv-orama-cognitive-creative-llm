package fr.ivorama.cognitive.agent;

import org.junit.jupiter.api.Test;

/** The same three independent scenarios are also wired into Maven/Surefire. */
public class ObserverAgentTest {
    @Test void javaAnalysisWithLineEvidence() throws Exception {
        ObserverThreeCases.caseJavaExceptionAnalysis();
    }
    @Test void navigationSpringMq() throws Exception {
        ObserverThreeCases.caseSpringMqNavigation();
    }
    @Test void readOnlyBoundaryRejectsTraversalAndSymlinks() throws Exception {
        ObserverThreeCases.caseReadOnlyAndPathIsolation();
    }
}
