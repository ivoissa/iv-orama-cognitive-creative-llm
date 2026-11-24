package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Three self-contained Java 21 acceptance scenarios; run without Ollama/JUnit. */
public final class ObserverThreeCases {
    private ObserverThreeCases() { }

    public static void main(String[] args) throws Exception {
        caseJavaExceptionAnalysis();
        caseSpringMqNavigation();
        caseReadOnlyAndPathIsolation();
        System.out.println("RESULT: 3/3 observer acceptance cases PASS");
    }

    public static void caseJavaExceptionAnalysis() throws Exception {
        Path root = Files.createTempDirectory("ivoc-java-");
        try {
            Files.createDirectories(root.resolve("src/main/java/demo"));
            Files.writeString(root.resolve("src/main/java/demo/OrderController.java"), """
                    package demo;
                    import java.util.List;
                    class OrderController {
                      String first(List<String> items) {
                        return items.get(0); // throws if items empty
                      }
                    }
                    """);
            String before = digest(root.resolve("src/main/java/demo/OrderController.java"));
            var choices = new ArrayDeque<>(List.of(
                    new ObserverSession.Action(ObserverSession.Operation.SEARCH, "", "items.get(0)", 1, 10),
                    new ObserverSession.Action(ObserverSession.Operation.READ, "src/main/java/demo/OrderController.java", "", 2, 5),
                    new ObserverSession.Action(ObserverSession.Operation.FINISH, "", "", 1, 1)));
            var session = new ObserverSession(new ReadOnlyWorkspace(root),
                    (q, evidence) -> choices.removeFirst(),
                    (q, evidence) -> "Risque d'accès au premier élément si la liste est vide [F1], " +
                            "confirmé par la méthode [F2]. Aucun test exécuté.");
            var report = session.investigate("Pourquoi items.get(0) peut provoquer une erreur ?");
            check(report.toolCalls() == 2, "Nombre de lectures");
            check(report.evidence().get(0).content().contains("OrderController.java:5:"), "Localisation ligne source");
            check(report.evidence().get(1).content().contains("items.get(0)"), "Lecture de méthode");
            check(report.unsupportedCitations().isEmpty() && !report.missingCitation(), "Citations contrôlées");
            check(before.equals(digest(root.resolve("src/main/java/demo/OrderController.java"))), "Fichier préservé");
            System.out.println("TEST 1 PASS — Java: SEARCH + READ, lignes et citations, aucun changement");
        } finally { deleteTree(root); }
    }

    public static void caseSpringMqNavigation() throws Exception {
        Path root = Files.createTempDirectory("ivoc-mq-");
        try {
            Files.createDirectories(root.resolve("src/main/java/demo"));
            Files.createDirectories(root.resolve("src/test/java/demo"));
            Files.writeString(root.resolve("src/main/java/demo/MqConsumer.java"), """
                    package demo;
                    class MqConsumer {
                        // Reconnect after channel not available
                        void receive() { String channel = "QM1"; }
                    }
                    """);
            Files.writeString(root.resolve("src/test/java/demo/MqConsumerTest.java"),
                    "package demo;\n" + "// channel not available\n".repeat(15) + "class MqConsumerTest {}\n");
            Files.writeString(root.resolve("README.md"), "Project: MQ channel not available\n");
            var planned = new ArrayDeque<>(List.of(
                    new ObserverSession.Action(ObserverSession.Operation.LIST, ".", "", 1, 1),
                    new ObserverSession.Action(ObserverSession.Operation.SEARCH, "", "channel not available", 1, 1),
                    new ObserverSession.Action(ObserverSession.Operation.FINISH, "", "", 1, 1)));
            var session = new ObserverSession(new ReadOnlyWorkspace(root),
                    (q, evidence) -> planned.removeFirst(),
                    (q, evidence) -> "Un consommateur MQ et un README mentionnent le canal [F1] [F2]. " +
                            "Cela ne prouve pas la cause d'une panne serveur.");
            var report = session.investigate("Localiser la gestion MQ dans le projet");
            check(report.toolCalls() == 2, "Deux opérations");
            check(report.evidence().get(0).content().contains("MqConsumerTest.java"), "Inventaire projet");
            check(report.evidence().get(1).content().startsWith("src/main/java/demo/MqConsumer.java"),
                    "Sources de production prioritaires sur occurrences de tests");
            check(report.evidence().get(1).content().contains("MqConsumer.java"), "Recherche multi-fichiers");
            check(report.unsupportedCitations().isEmpty(), "Pas de citation fictive");
            check(!report.evidenceLimitReached(), "Budget respecté");
            System.out.println("TEST 2 PASS — MQ: navigation multi-fichiers, traces et limites d'inférence");
        } finally { deleteTree(root); }
    }

    public static void caseReadOnlyAndPathIsolation() throws Exception {
        Path root = Files.createTempDirectory("ivoc-isolation-");
        Path outside = Files.createTempFile("ivoc-secret-outside-", ".java");
        try {
            Files.writeString(outside, "NEVER_EXPOSE_ME");
            Files.writeString(root.resolve("Allowed.java"), "class Allowed { String message = \"not a command\"; }\n");
            Files.writeString(root.resolve(".env"), "API_KEY=abc123\n");
            Files.writeString(root.resolve("passwords.properties"), "password=abc123\n");
            Files.writeString(root.resolve("application.yml"), "server:\n  port: 8080\n  password: VERY_PRIVATE_DATA\n");
            Path link = root.resolve("Link.java");
            boolean symlinkCreated = SymlinkFixture.createIfSupported(link, outside);
            var workspace = new ReadOnlyWorkspace(root);
            String listing = workspace.list(".").content();
            check(listing.contains("Allowed.java"), "Code visible");
            check(!listing.contains("passwords.properties") && !listing.contains(".env"),
                    "Exclusions secrètes et symlinks");
            String configExcerpt = workspace.read("application.yml", 1, 6).content();
            check(configExcerpt.contains("[REDACTED") && !configExcerpt.contains("VERY_PRIVATE_DATA"), "Masquage config");
            check(!workspace.search("password").content().contains("VERY_PRIVATE_DATA"), "Masquage recherche");
            expectRejection(() -> workspace.read("../" + outside.getFileName(), 1, 1), "Traversal");
            if (symlinkCreated) {
                check(!listing.contains("Link.java"), "Symlink absent de la liste");
                expectRejection(() -> workspace.read("Link.java", 1, 1), "Symlink");
            }
            expectRejection(() -> workspace.read(".env", 1, 1), "Dotfile");
            expectRejection(() -> workspace.read("passwords.properties", 1, 1), "Secret file");
            var planner = new ObserverSession.Planner() {
                @Override public ObserverSession.Action nextAction(String q, List<ObserverSession.Evidence> e) {
                    return new ObserverSession.Action(ObserverSession.Operation.READ, "../" + outside.getFileName(), "", 1, 2);
                }
            };
            String before = digest(root.resolve("Allowed.java"));
            expectRejection(() -> new ObserverSession(workspace, planner, (q, e) -> "wrong").investigate("Read outside"),
                    "No unsafe observer action");
            check(before.equals(digest(root.resolve("Allowed.java"))), "Aucune écriture");
            var cite = new ObserverSession(workspace,
                    (q, e) -> e.isEmpty() ? new ObserverSession.Action(ObserverSession.Operation.LIST, ".", "", 1, 1)
                            : new ObserverSession.Action(ObserverSession.Operation.FINISH, "", "", 1, 1),
                    (q, e) -> "Citation créée par erreur [F999]").investigate("Lister");
            check(cite.unsupportedCitations().contains("F999"), "Audit de citations fabriquées");
            // A malicious document cannot create a native shell action: the action type is a closed enum.
            check(Arrays.stream(ObserverSession.Operation.values()).noneMatch(o -> o.name().equals("EXEC")),
                    "Pas d'outil d'exécution");
            System.out.println("TEST 3 PASS — traversal / lien / fichiers sensibles refusés, aucune écriture");
        } finally { deleteTree(root); Files.deleteIfExists(outside); }
    }

    private static String digest(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private interface Throwing { void run() throws Exception; }
    private static void expectRejection(Throwing action, String message) throws Exception {
        try { action.run(); throw new AssertionError("Doit refuser : " + message); }
        catch (SecurityException | IllegalArgumentException expected) { }
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
