package fr.ivorama.cognitive.agent;

import java.nio.file.*;
import java.util.*;

/** Deterministic regressions, runnable without Maven, external libraries or Ollama. */
public final class AuditRegressionCases {
    private static final String PATH = "src/main/java/demo/A.java";
    private AuditRegressionCases() { }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += run("R1 ancre Windows CRLF", AuditRegressionCases::windowsAnchor);
        failures += run("R2 preuves incomplètes ou discontinues", AuditRegressionCases::incompleteEvidence);
        failures += run("R3 secrets XML/YAML/PEM multi-lignes", AuditRegressionCases::multilineSecrets);
        failures += run("R4 lectures hors limites", AuditRegressionCases::readBounds);
        failures += run("R5 budget des cinq opérations", AuditRegressionCases::actionBudget);
        System.out.println("RESULT: " + (5 - failures) + "/5 audit regression cases PASS");
        if (failures != 0) throw new AssertionError(failures + " regression(s)");
    }

    public static void windowsAnchor() throws Exception {
        Path root = Files.createTempDirectory("ivoc-crlf-");
        try {
            Path file = root.resolve(PATH);
            Files.createDirectories(file.getParent());
            String original = "package demo;\r\nclass A {\r\n  int f() {\r\n    return 1;\r\n  }\r\n}\r\n";
            Files.writeString(file, original);
            var read = new ReadOnlyWorkspace(root).read(PATH, 1, 10);
            var edit = new PatchStager.Edit(PATH, "  int f() {\r\n    return 1;", "  int f() {\r\n    return 2;");
            ObservedPatchGuard.requireWitnessed(List.of(evidence(read.content(), false)), List.of(edit));
            try (var patch = new PatchStager().prepare(root, List.of(edit))) {
                check(Files.readString(patch.stagingDirectory().resolve(PATH)).equals(original.replace("return 1", "return 2")),
                        "Exact CRLF replacement");
            }
            check(Files.readString(file).equals(original), "Original source preserved");
        } finally { remove(root); }
    }

    public static void incompleteEvidence() {
        rejectWitness(PATH + ":1: return 1;", "return 1;"); // budget-cut line without terminator
        rejectWitness(PATH + ":1: one\n" + PATH + ":3: two\n", "one\ntwo");
        rejectWitness(PATH + ":1: prefix…\n", "prefix…");
        rejectWitness(PATH + ":1: [REDACTED: sensitive assignment]\n", "[REDACTED");
        rejectWitness(PATH + ":2: target\n", "target\n"); // synthetic newline at source EOF
        // Consecutive, complete lines still authorize a multi-line anchor.
        ObservedPatchGuard.requireWitnessed(List.of(evidence(PATH + ":1: one\n" + PATH + ":2: two\n", false)),
                List.of(new PatchStager.Edit(PATH, "one\ntwo", "three")));
    }

    public static void multilineSecrets() throws Exception {
        Path root = Files.createTempDirectory("ivoc-multiline-");
        try {
            Files.writeString(root.resolve("settings.xml"), "<settings>\n<password>\nXML_VALUE_915\n</password>\n<port>8080</port>\n</settings>\n");
            Files.writeString(root.resolve("application.yml"), "password: |-\n  YAML_VALUE_915\nserver:\n  port: 8080\n");
            Files.writeString(root.resolve("notes.txt"), "-----BEGIN PRIVATE KEY-----\nPEM_VALUE_915\n-----END PRIVATE KEY-----\npublic data\n");
            var workspace = new ReadOnlyWorkspace(root);
            for (String name : List.of("settings.xml", "application.yml", "notes.txt")) {
                String visible = workspace.read(name, 2, 1).content();
                check(!visible.contains("VALUE_915"), "Partial READ must redact using full-document context");
            }
            for (String value : List.of("XML_VALUE_915", "YAML_VALUE_915", "PEM_VALUE_915"))
                check(workspace.search(value).content().isEmpty(), "SEARCH must not reveal multiline values");
            check(workspace.search("8080").content().contains("8080"), "Public configuration remains visible");
        } finally { remove(root); }
    }

    public static void readBounds() throws Exception {
        Path root = Files.createTempDirectory("ivoc-read-bound-");
        try {
            Files.writeString(root.resolve("A.java"), "class A {}");
            Files.writeString(root.resolve("Large.java"), "x".repeat(96 * 1024 + 1));
            var workspace = new ReadOnlyWorkspace(root);
            expect(IllegalArgumentException.class, () -> workspace.read("A.java", 2, 1));
            expect(SecurityException.class, () -> workspace.read("Large.java", 1, 1));
        } finally { remove(root); }
    }

    public static void actionBudget() throws Exception {
        Path root = Files.createTempDirectory("ivoc-budget-");
        try {
            Files.writeString(root.resolve("A.java"), "// line\n".repeat(6));
            int[] calls = {0};
            var session = new ObserverSession(new ReadOnlyWorkspace(root),
                    (q, e) -> new ObserverSession.Action(ObserverSession.Operation.READ, "A.java", "", ++calls[0], 1),
                    (q, e) -> "Observation [F1]");
            var report = session.investigate("Inspecter le fichier");
            check(report.toolCalls() == 5 && calls[0] == 5 && report.evidenceLimitReached(), "Five-operation limit reported");
        } finally { remove(root); }
    }

    private static ObserverSession.Evidence evidence(String content, boolean truncated) {
        return new ObserverSession.Evidence("F1", "READ", PATH, content, 1, truncated);
    }
    private static void rejectWitness(String content, String anchor) {
        try {
            ObservedPatchGuard.requireWitnessed(List.of(evidence(content, true)), List.of(new PatchStager.Edit(PATH, anchor, "replacement")));
            throw new AssertionError("Fabricated witness accepted");
        } catch (SecurityException expected) { }
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static int run(String name, Checked body) {
        try { body.run(); System.out.println(name + " PASS"); return 0; }
        catch (Exception | AssertionError ex) { System.out.println(name + " FAIL: " + ex); return 1; }
    }
    private static void expect(Class<? extends Exception> type, Checked body) throws Exception {
        try { body.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (Exception ex) { if (!type.isInstance(ex)) throw ex; }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void remove(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
