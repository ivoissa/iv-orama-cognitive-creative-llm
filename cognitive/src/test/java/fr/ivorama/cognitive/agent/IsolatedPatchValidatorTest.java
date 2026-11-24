package fr.ivorama.cognitive.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Maven/Java subprocesses and independent tests; no Qwen in this suite. */
class IsolatedPatchValidatorTest {
    @TempDir Path root;
    private static final String FILE = "src/main/java/demo/Counts.java";
    private static final String ANCHOR = "return values.size();";
    private void fixture(boolean alreadyCorrect) throws Exception {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve("src/test/java/demo"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>agent-validation-fixture</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.11.4</version><scope>test</scope></dependency></dependencies>
                <build><plugins>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
                </plugins></build></project>
                """);
        Files.writeString(root.resolve(FILE), "package demo; public class Counts { public static int count(java.util.List<Integer> values) { " +
                (alreadyCorrect ? "if (values == null) return 0; " : "") + ANCHOR + " } }\n");
        Files.writeString(root.resolve("src/test/java/demo/IndependentContractTest.java"), """
                package demo;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.*;
                class IndependentContractTest {
                    @Test void nullIsZero() { assertEquals(0, Counts.count(null)); }
                    @Test void nonemptyRetainsItsSize() { assertEquals(2, Counts.count(java.util.List.of(4, 5))); }
                }
                """);
    }
    private IsolatedPatchValidator.Plan plan(int seconds) {
        return new IsolatedPatchValidator.Plan(System.getProperty("ivorama.agent.maven", "mvn"),
                "IndependentContractTest", Duration.ofSeconds(seconds), false);
    }
    private IsolatedPatchValidator.Result validate(String replacement) throws Exception {
        String original = Files.readString(root.resolve(FILE));
        try (var patch = new PatchStager().prepare(root, List.of(new PatchStager.Edit(FILE, ANCHOR, replacement)))) {
            var result = new IsolatedPatchValidator().validate(root, patch, plan(60));
            assertEquals(original, Files.readString(root.resolve(FILE)), "Original repository unchanged");
            return result;
        }
    }
    @Test void verifiesReproducedFailureAndPassingIndependentTests() throws Exception {
        fixture(false);
        var result = validate("return values == null ? 0 : values.size();");
        assertEquals(IsolatedPatchValidator.Status.FIX_VERIFIED, result.status(), result.toString());
        assertEquals(2, result.baseline().tests());
        assertEquals(1, result.baseline().errors());
        assertEquals(2, result.candidate().tests());
        assertEquals(0, result.candidate().failures() + result.candidate().errors());
    }
    @Test void catchesACompilingPatchThatBreaksTheNonemptyCase() throws Exception {
        fixture(false);
        var result = validate("return 0;");
        assertEquals(IsolatedPatchValidator.Status.CANDIDATE_FAILED, result.status(), result.toString());
        assertEquals(1, result.candidate().failures());
    }
    @Test void syntaxOnlyAcceptanceDoesNotHideATypeError() throws Exception {
        fixture(false);
        var result = validate("return \"incorrect type\";");
        assertEquals(IsolatedPatchValidator.Status.CANDIDATE_FAILED, result.status(), result.toString());
        assertEquals(0, result.candidate().tests());
    }
    @Test void doesNotCreditAFixWhenTheFailureWasNotReproduced() throws Exception {
        fixture(true);
        var result = validate("return 0;");
        assertEquals(IsolatedPatchValidator.Status.BASELINE_NOT_REPRODUCED, result.status(), result.toString());
        assertNull(result.candidate());
    }
    @Test void timeoutStopsBeforeAcceptingAnyPatch() throws Exception {
        fixture(false);
        try (var patch = new PatchStager().prepare(root, List.of(new PatchStager.Edit(FILE, ANCHOR, "return 0;")))) {
            long start = System.nanoTime();
            var result = new IsolatedPatchValidator().validate(root, patch, plan(1));
            assertEquals(IsolatedPatchValidator.Status.TIMEOUT, result.status());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(8)) < 0);
        }
    }
    @Test void testNamesCannotInjectMavenArguments() {
        assertThrows(IllegalArgumentException.class, () -> new IsolatedPatchValidator.Plan("mvn", "X -DskipTests", Duration.ofSeconds(20), false));
    }
}
