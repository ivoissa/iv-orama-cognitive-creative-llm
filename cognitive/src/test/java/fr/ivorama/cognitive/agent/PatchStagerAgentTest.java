package fr.ivorama.cognitive.agent;

import org.junit.jupiter.api.Test;

/** Same three acceptance cases under Maven/Surefire. */
public class PatchStagerAgentTest {
    @Test void safeJavaReplacement() throws Exception { PatchStagerThreeCases.caseSafeJavaFix(); }
    @Test void syntaxAndAnchorValidation() throws Exception { PatchStagerThreeCases.caseBrokenSyntaxAndAmbiguousAnchors(); }
    @Test void safetyAndNoOriginalWrites() throws Exception { PatchStagerThreeCases.caseWorkspaceIsolationAndCleanup(); }
}
