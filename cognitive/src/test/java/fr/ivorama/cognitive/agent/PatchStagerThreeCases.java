package fr.ivorama.cognitive.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reproducible Java 21 acceptance tests; no Maven, Ollama or external dependencies required. */
public final class PatchStagerThreeCases {
    private PatchStagerThreeCases() { }
    public static void main(String[] args) throws Exception {
        caseSafeJavaFix();
        caseBrokenSyntaxAndAmbiguousAnchors();
        caseWorkspaceIsolationAndCleanup();
        System.out.println("RESULT: 3/3 patch-proposal acceptance cases PASS");
    }
    public static void caseSafeJavaFix() throws Exception {
        Path root = Files.createTempDirectory("ivoc-propose-java-");
        try {
            Path file = source(root, "src/main/java/demo/OrderService.java", """
                    package demo;
                    import java.util.List;
                    class OrderService {
                        String first(List<String> items) {
                            return items.get(0);
                        }
                    }
                    """);
            String original = digest(file);
            var edit = new PatchStager.Edit("src/main/java/demo/OrderService.java",
                    "return items.get(0);", "return items.isEmpty() ? null : items.get(0);");
            var evidence = new ObserverSession.Evidence("F1", "READ", "src/main/java/demo/OrderService.java",
                    "src/main/java/demo/OrderService.java:5:             return items.get(0);\n", 1, false);
            ObservedPatchGuard.requireWitnessed(List.of(evidence), List.of(edit));
            Path stagedLocation;
            try (var patch = new PatchStager().prepare(root, List.of(edit))) {
                check(patch.files().size() == 1, "Exactly one staged file");
                check(patch.syntaxValid(), "Java parser accepts proposed fix");
                check(patch.files().getFirst().preview().contains("+return items.isEmpty()"), "Preview has replacement");
                check(original.equals(patch.files().getFirst().originalSha256()), "Original SHA correctly recorded");
                stagedLocation = patch.stagingDirectory();
                check(Files.readString(stagedLocation.resolve(edit.path())).contains("items.isEmpty()"), "Temp copy changed");
                check(original.equals(digest(file)), "Original file never changed");
            }
            check(!Files.exists(stagedLocation), "Staging directory removed on close");
            System.out.println("TEST 1 PASS — valid Java patch, parse OK, SHA/diff and original unchanged");
        } finally { remove(root); }
    }
    public static void caseBrokenSyntaxAndAmbiguousAnchors() throws Exception {
        Path root = Files.createTempDirectory("ivoc-propose-valid-");
        try {
            Path file = source(root, "src/test/java/demo/ParserTest.java",
                    "package demo; class ParserTest { int f(){ return 1; } }\n");
            Path file2 = source(root, "src/main/java/demo/Duplicate.java",
                    "package demo; class Duplicate { int a(){return 1;} int b(){return 1;} }\n");
            var evidence = new ObserverSession.Evidence("F1", "READ", "src/test/java/demo/ParserTest.java",
                    "src/test/java/demo/ParserTest.java:1: package demo; class ParserTest { int f(){ return 1; } }\n", 1, false);
            reject(() -> ObservedPatchGuard.requireWitnessed(List.of(evidence), List.of(new PatchStager.Edit(
                    "src/test/java/demo/ParserTest.java", "System.exit(0);", "return 2;"))), "Unobserved anchor");
            String before = digest(file);
            var invalid = new PatchStager.Edit("src/test/java/demo/ParserTest.java", "return 1;", "return ( ;");
            try (var staged = new PatchStager().prepare(root, List.of(invalid))) {
                check(!staged.syntaxValid(), "Parser rejects malformed Java");
                check(!staged.files().getFirst().parseErrors().isEmpty(), "Diagnostic provided");
            }
            reject(() -> new PatchStager().prepare(root, List.of(new PatchStager.Edit(
                    "src/main/java/demo/Duplicate.java", "return 1;", "return 2;"))), "Ambiguous anchor rejected");
            reject(() -> new PatchStager().prepare(root, List.of(
                    new PatchStager.Edit("src/test/java/demo/ParserTest.java", "return 1;", "return 2;"),
                    new PatchStager.Edit("src/test/java/demo/ParserTest.java", "return 1;", "return 3;"))),
                    "Duplicate path rejected");
            check(before.equals(digest(file)) && Files.exists(file2), "No repository mutation");
            System.out.println("TEST 2 PASS — syntax error detected, duplicate anchors and edits rejected");
        } finally { remove(root); }
    }
    public static void caseWorkspaceIsolationAndCleanup() throws Exception {
        Path root = Files.createTempDirectory("ivoc-propose-security-");
        Path outside = Files.createTempFile("ivoc-propose-outside-", ".java");
        try {
            Path allowed = source(root, "src/main/java/demo/Allowed.java", "package demo; class Allowed {}\n");
            Files.writeString(outside, "class Outside {}\n");
            String linkPath = "src/main/java/demo/Link.java";
            boolean symlinkCreated = SymlinkFixture.createIfSupported(root.resolve(linkPath), outside);
            Files.createDirectories(root.resolve("src/main/resources"));
            Files.writeString(root.resolve("src/main/resources/application.yml"), "password: TOP_SECRET\n");
            String before = digest(allowed);
            var stager = new PatchStager();
            for (String path : List.of("src/main/java/../../../../escape.java",
                    "/etc/passwd", "src/main/resources/application.yml",
                    "src/main/java/demo/../Allowed.java", "src/main/java/demo/NoFile.java")) {
                reject(() -> stager.prepare(root, List.of(new PatchStager.Edit(path, "class", "interface"))),
                        "Blocked path: " + path);
            }
            reject(() -> stager.prepare(root, List.of(new PatchStager.Edit(
                    "src/main/java/demo/Allowed.java", "missing anchor", "change"))), "Absent anchor rejected");
            if (symlinkCreated) {
                reject(() -> stager.prepare(root, List.of(new PatchStager.Edit(linkPath, "class", "interface"))),
                        "Symlink rejected");
            }
            // Valid preview with secret-looking literal: preview must not repeat assignment data.
            var assignment = new PatchStager.Edit("src/main/java/demo/Allowed.java", "class Allowed {}",
                    "class Allowed { String password = \"PROTECTED_VALUE\"; }");
            try (var draft = stager.prepare(root, List.of(assignment))) {
                check(!draft.files().getFirst().preview().contains("PROTECTED_VALUE"), "Secret-looking assignment redacted");
            }
            check(before.equals(digest(allowed)), "Original unchanged after malicious proposals");
            check(Files.readString(outside).equals("class Outside {}\n"), "Outside file unaffected");
            System.out.println("TEST 3 PASS — traversal, symlink and resource edits refused, redacted preview");
        } finally { remove(root); Files.deleteIfExists(outside); }
    }
    private static Path source(Path root, String name, String data) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent());
        Files.writeString(file, data, StandardCharsets.UTF_8); return file;
    }
    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
    private static void check(boolean condition, String why) { if (!condition) throw new AssertionError(why); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void reject(Action action, String why) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + why); }
        catch (IllegalArgumentException | SecurityException expected) { }
    }
    private static void remove(Path p) throws Exception {
        if (Files.exists(p)) try (var stream = Files.walk(p)) {
            for (Path child: stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(child);
        }
    }
}
