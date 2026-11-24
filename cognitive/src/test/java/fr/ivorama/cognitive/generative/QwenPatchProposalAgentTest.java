package fr.ivorama.cognitive.generative;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

/** Contract tests with scripted Qwen responses, executed by Maven local-llm profile. */
public class QwenPatchProposalAgentTest {
    private static final String PATH = "src/main/java/demo/OrderService.java";
    private static final String CODE = """
            package demo;
            class OrderService {
              int first(java.util.List<Integer> items) {
                return items.get(0);
              }
            }
            """;

    @Test void acceptsOnlyReadAnchoredPatchWithoutWriting() throws Exception {
        Path root = project();
        try {
            var planner = scripted(action("READ", PATH, 1, 8), action("FINISH", "", 1, 1));
            var patch = scripted("""
                    {"rationale":"Protection d'une liste vide","edits":[
                       {"path":"src/main/java/demo/OrderService.java",
                        "oldText":"return items.get(0);",
                        "newText":"return items.isEmpty() ? -1 : items.get(0);"}]}
                    """);
            String original = Files.readString(root.resolve(PATH));
            var result = new QwenPatchProposalAgent(root, planner, patch).propose("Empêcher une exception sur liste vide");
            assertEquals(1, result.stagedFiles().size());
            assertTrue(result.syntaxValid());
            assertEquals(original, Files.readString(root.resolve(PATH)));
            assertEquals(2, result.observations().toolCalls(), "Découverte LIST puis READ");
        } finally { clean(root); }
    }

    @Test void refusesMalformedQwenJson() throws Exception {
        Path root = project();
        try {
            var planner = scripted(action("READ", PATH, 1, 8), action("FINISH", "", 1, 1));
            var patch = scripted("{\"rationale\":\"incorrect\",\"edits\":\"not-an-array\"}");
            assertThrows(IllegalArgumentException.class,
                    () -> new QwenPatchProposalAgent(root, planner, patch).propose("Corriger la méthode"));
        } finally { clean(root); }
    }

    @Test void refusesUnobservedEditEvenInsideReadFile() throws Exception {
        Path root = project();
        try {
            var planner = scripted(action("READ", PATH, 1, 2), action("FINISH", "", 1, 1));
            var patch = scripted("""
                    {"rationale":"ancre non lue","edits":[
                       {"path":"src/main/java/demo/OrderService.java",
                        "oldText":"return items.get(0);",
                        "newText":"return 0;"}]}
                    """);
            assertThrows(SecurityException.class,
                    () -> new QwenPatchProposalAgent(root, planner, patch).propose("Corriger la méthode"));
            assertEquals(CODE, Files.readString(root.resolve(PATH)));
        } finally { clean(root); }
    }
    @Test void emptyEditsAreAnExplicitAbstention() throws Exception {
        Path root = project();
        try {
            var planner = scripted(action("READ", PATH, 1, 8), action("FINISH", "", 1, 1));
            var patch = scripted("{\"rationale\":\"preuves insuffisantes\",\"edits\":[]}");
            var result = new QwenPatchProposalAgent(root, planner, patch).propose("Vérifier la méthode");
            assertTrue(result.stagedFiles().isEmpty());
            assertFalse(result.syntaxValid(), "An abstention does not validate any Java patch");
            assertEquals("preuves insuffisantes", result.rationale());
            assertEquals(CODE, Files.readString(root.resolve(PATH)));
        } finally { clean(root); }
    }
    @Test void longDisplayRationaleDoesNotRejectAnOtherwiseValidPatch() throws Exception {
        Path root = project();
        try {
            var planner = scripted(action("READ", PATH, 1, 8), action("FINISH", "", 1, 1));
            String reply = "{\"rationale\":\"" + "Explication non certifiée. ".repeat(70) +
                    "\",\"edits\":[{\"path\":\"" + PATH + "\",\"oldText\":\"return items.get(0);\",\"newText\":\"return items.isEmpty() ? -1 : items.get(0);\"}]}";
            var result = new QwenPatchProposalAgent(root, planner, scripted(reply)).propose("Gérer une liste vide");
            assertTrue(result.syntaxValid());
            assertTrue(result.rationale().length() <= 1000);
            assertTrue(result.rationale().endsWith("[explication abrégée]"));
            assertEquals(CODE, Files.readString(root.resolve(PATH)));
        } finally { clean(root); }
    }
    private static String action(String op, String path, int line, int lines) {
        return "{\"operation\":\""+op+"\",\"path\":\""+path+"\",\"query\":\"\",\"startLine\":"+line+",\"lines\":"+lines+"}";
    }
    private static GenerationBackend scripted(String... responses) {
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(responses));
        return (messages, partial) -> {
            if(queue.isEmpty())throw new AssertionError("Extra Qwen generation");
            return new GenerationBackend.Result(queue.removeFirst(), "scripted", "STOP", 1L, -1L, 1, 1);
        };
    }
    private static Path project() throws IOException {
        Path dir = Files.createTempDirectory("ivoc-mock-qwen-");
        Path source = dir.resolve(PATH);
        Files.createDirectories(source.getParent());
        Files.writeString(source, CODE);
        return dir;
    }
    private static void clean(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
        }
    }
}
