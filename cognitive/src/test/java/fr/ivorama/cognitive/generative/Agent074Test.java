package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.agent.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Agent074Test {
    @TempDir Path root;
    private static final List<ObserverSession.Evidence> EVIDENCE = List.of(
            new ObserverSession.Evidence("F1", "READ", "Foo.java", "Foo.java:3: return items.get(0);\n", 1, false));
    private static String claim(String kind, String id, String quote) {
        return "{\"claims\":[{\"kind\":\""+kind+"\",\"text\":\"Accès au premier élément\",\"sources\":[{\"id\":\""+id+"\",\"quote\":\""+quote+"\"}]}]}";
    }
    @Test void exactExcerptIsRenderedWithQualifiedStatus() {
        String rendered = EvidenceSynthesis.validateAndRender(claim("OBSERVED", "F1", "return items.get(0);"), EVIDENCE);
        assertTrue(rendered.contains("[F1]"));
        assertTrue(rendered.contains("sens non certifié"));
    }
    @Test void existingCitationDoesNotPermitInventedQuote() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceSynthesis.validateAndRender(
                claim("OBSERVED", "F1", "return items.isEmpty();"), EVIDENCE));
    }
    @Test void nonexistentCitationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EvidenceSynthesis.validateAndRender(
                claim("INFERRED", "F99", "return items.get(0);"), EVIDENCE));
    }
    @Test void assertionsRequireSourcesButUnknownCanAbstain() {
        String response = "{\"claims\":[{\"kind\":\"UNKNOWN\",\"text\":\"Tests non exécutés\",\"sources\":[]}]}";
        assertTrue(EvidenceSynthesis.validateAndRender(response, List.of()).contains("Information manquante"));
        assertThrows(IllegalArgumentException.class, () -> EvidenceSynthesis.validateAndRender(response.replace("UNKNOWN", "OBSERVED"), EVIDENCE));
    }
    @Test void duplicatePropertiesAndTrailingJsonAreRejected() {
        String good = claim("HYPOTHESIS", "F1", "return items.get(0);");
        assertThrows(IllegalArgumentException.class, () -> EvidenceSynthesis.validateAndRender(good + "{}", EVIDENCE));
        assertThrows(IllegalArgumentException.class, () -> EvidenceSynthesis.validateAndRender(good.replace("\"kind\":", "\"kind\":\"UNKNOWN\",\"kind\":"), EVIDENCE));
    }
    @Test void provenanceCheckDoesNotPretendToProveEntailment() {
        String misleading = claim("INFERRED", "F1", "return items.get(0);").replace("Accès au premier élément", "Cette méthode est toujours correcte");
        assertTrue(EvidenceSynthesis.validateAndRender(misleading, EVIDENCE).contains("Déduction non certifiée"));
    }
    @Test void readOnlyObserverRejectsUnstructuredSynthesis() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}\n");
        var planner = scripted(action("READ", "Foo.java", "", 1, 1), action("FINISH", "", "", 1, 1));
        var agent = new QwenObserverAgent(root, planner, scripted("Le code fonctionne [F1]"));
        assertThrows(IllegalArgumentException.class, () -> agent.investigate("Analyser Foo"));
    }
    @Test void equivalentSearchWithDifferentUnusedFieldsCannotLoop() throws Exception {
        Files.writeString(root.resolve("Foo.java"), "class Foo {}\n");
        var actions = new ArrayDeque<>(List.of(new ObserverSession.Action(ObserverSession.Operation.SEARCH,"","absent",1,4),
                new ObserverSession.Action(ObserverSession.Operation.SEARCH,"ignored","ABSENT",9,17)));
        var session = new ObserverSession(new ReadOnlyWorkspace(root), (q,e) -> actions.removeFirst(), (q,e)->"inconnu");
        assertThrows(IllegalStateException.class, () -> session.investigate("Trouver absent"));
    }
    @Test void emptySearchProducesExplicitProgressStateForNextPlan() throws Exception {
        var count = new int[1];
        GenerationBackend planner = (messages, partial) -> {
            if (count[0]++ == 0) return result(action("SEARCH", "", "absent", 1, 1));
            String context = ((dev.langchain4j.data.message.UserMessage)messages.getLast()).singleText();
            assertTrue(context.contains("emptySearches\":[\"absent\"]"));
            assertTrue(context.contains("remainingToolActions\":3"));
            return result(action("FINISH", "", "", 1, 1));
        };
        var summary = scripted("{\"claims\":[{\"kind\":\"UNKNOWN\",\"text\":\"Information non trouvée\",\"sources\":[]}]}" );
        assertTrue(new QwenObserverAgent(root, planner, summary).investigate("Trouver absent").answer().contains("Information manquante"));
    }
    @Test void readCanOmitUnusedFieldsAndResolvesOnlyADiscoveredUniqueBasename() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("src/main/java/demo/Foo.java"), "class Foo { int x = 3; }\n");
        var planner = scripted("{\"operation\":\"READ\",\"path\":\"Foo.java\"}", "{\"operation\":\"FINISH\",\"reasoning\":\"Lecture terminée, conclusion à vérifier\"}");
        var summary = scripted(claim("OBSERVED", "F2", "class Foo { int x = 3; }"));
        var report = new QwenObserverAgent(root, planner, summary).investigate("Lire Foo.java");
        assertEquals(2, report.toolCalls());
        assertEquals("src/main/java/demo/Foo.java", report.evidence().get(1).request());
        assertTrue(report.unsupportedCitations().isEmpty());
    }
    @Test void ambiguousOrTraversalPathsAreNotRepairedIntoAnArbitraryFile() throws Exception {
        Files.createDirectories(root.resolve("a"));Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/Foo.java"), "class Foo {}\n");Files.writeString(root.resolve("b/Foo.java"), "class Foo {}\n");
        for (String path : List.of("Foo.java", "../a/Foo.java")) {
            var planner = scripted("{\"operation\":\"READ\",\"path\":\""+path+"\"}");
            assertThrows(IllegalStateException.class, () -> new QwenObserverAgent(root, planner, scripted("unused")).investigate("Lire Foo"));
        }
    }
    @Test void normalizingUnusedFieldsDoesNotAcceptUnknownCommands() throws Exception {
        var planner = scripted("{\"operation\":\"FINISH\",\"command\":\"touch something\"}");
        assertThrows(IllegalStateException.class, () -> new QwenObserverAgent(root, planner, scripted("unused")).investigate("Vérifier"));
    }
    @Test void oneFormattingRepairMustStillProduceAnExactObservedAnchor() throws Exception {
        String path = "src/main/java/demo/Foo.java";
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), "package demo; class Foo { int x() { return 1; } }\n");
        String prefix = "{\"rationale\":\"Fixture\",\"edits\":[{\"path\":\""+path+"\",\"oldText\":\"";
        String suffix = "\",\"newText\":\"return 2;\"}]}";
        var planner = scripted("{\"operation\":\"READ\",\"path\":\"Foo.java\"}", "{\"operation\":\"FINISH\"}");
        var patch = scripted(prefix+"return   1;"+suffix, prefix+"return 1;"+suffix);
        var result = new QwenPatchProposalAgent(root, planner, patch).propose("Changer la valeur");
        assertTrue(result.syntaxValid());
        assertTrue(result.stagedFiles().getFirst().preview().contains("+return 2;"));
        // A second bad anchor is terminal, rather than starting an unbounded repair loop.
        var badPlanner = scripted("{\"operation\":\"READ\",\"path\":\"Foo.java\"}", "{\"operation\":\"FINISH\"}");
        var badPatch = scripted(prefix+"return   1;"+suffix, prefix+"return   1;"+suffix);
        assertThrows(SecurityException.class, () -> new QwenPatchProposalAgent(root, badPlanner, badPatch).propose("Changer la valeur"));
    }
    static GenerationBackend scripted(String... replies) {
        var queue = new ArrayDeque<>(List.of(replies));
        return (messages, partial) -> result(queue.removeFirst());
    }
    static GenerationBackend.Result result(String text) { return new GenerationBackend.Result(text,"fixture", "STOP",1,-1,1,1); }
    static String action(String op, String path, String query, int line, int lines) {
        return "{\"operation\":\""+op+"\",\"path\":\""+path+"\",\"query\":\""+query+"\",\"startLine\":"+line+",\"lines\":"+lines+"}";
    }
}
