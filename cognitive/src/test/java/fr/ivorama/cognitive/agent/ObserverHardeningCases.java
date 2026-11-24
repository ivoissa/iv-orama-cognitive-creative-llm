package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Adversarial deterministic cases added in 0.7.2-audit: secret oracle, key-name variants, syntax-check fallback. */
public final class ObserverHardeningCases {
    private ObserverHardeningCases() { }

    public static void main(String[] args) throws Exception {
        int failures = 0;
        failures += run("H1 recherche sans oracle de secret", ObserverHardeningCases::caseSearchIsNotAnOracle);
        failures += run("H2 variantes de noms de clés sensibles", ObserverHardeningCases::caseSensitiveKeyVariants);
        failures += run("H3 vérification syntaxique sans crash", ObserverHardeningCases::caseSyntaxCheckDegradesCleanly);
        System.out.println(failures == 0 ? "RESULT: 3/3 hardening cases PASS" : "RESULT: " + failures + " hardening case(s) FAIL");
        if (failures != 0) System.exit(1);
    }

    private interface Body { void run() throws Exception; }
    private static int run(String name, Body body) {
        try { body.run(); System.out.println(name + " PASS"); return 0; }
        catch (Throwable t) { System.out.println(name + " FAIL — " + t); return 1; }
    }

    public static void caseSearchIsNotAnOracle() throws Exception {
        Path root = Files.createTempDirectory("ivoc-oracle-");
        try {
            Files.writeString(root.resolve("application.yml"), "server:\n  port: 8080\n  password: VERY_PRIVATE_DATA\n");
            Files.writeString(root.resolve("App.java"), "class App { int port = 8080; }\n");
            var ws = new ReadOnlyWorkspace(root);
            // A guess that is right must be indistinguishable from a guess that is wrong.
            String right = ws.search("VERY_PRIVATE_DATA").content();
            String wrong = ws.search("WRONG_GUESS_XYZ").content();
            check(right.equals(wrong), "La recherche confirme l'existence d'un secret : [" + right.strip() + "]");
        } finally { deleteTree(root); }
    }

    public static void caseSensitiveKeyVariants() throws Exception {
        Path root = Files.createTempDirectory("ivoc-keys-");
        try {
            Files.writeString(root.resolve("application.properties"),
                    "jwt.secret.key=TOPSECRET99\nsecretKey=ABC123SECRET\nDB_PASSWORD_PROD=PRODPASS7\n"
                            + "passwordHash: HASH555\nserver.port=8080\n");
            var ws = new ReadOnlyWorkspace(root);
            String text = ws.read("application.properties", 1, 10).content();
            for (String leaked : List.of("TOPSECRET99", "ABC123SECRET", "PRODPASS7", "HASH555"))
                check(!text.contains(leaked), "Valeur sensible exposée par READ : " + leaked);
            check(text.contains("server.port=8080"), "Configuration non sensible conservée");
            check(ws.search("TOPSECRET99").content().isEmpty(), "Valeur retrouvable par SEARCH");
        } finally { deleteTree(root); }
    }

    public static void caseSyntaxCheckDegradesCleanly() throws Exception {
        Path root = Files.createTempDirectory("ivoc-syntax-");
        try {
            Files.createDirectories(root.resolve("src/main/java/demo"));
            Files.writeString(root.resolve("src/main/java/demo/A.java"),
                    "package demo;\nclass A { int f(java.util.List<String> l) { return l.size(); } }\n");
            var good = List.of(new PatchStager.Edit("src/main/java/demo/A.java", "return l.size();", "return l.isEmpty() ? 0 : l.size();"));
            try (var p = new PatchStager().prepare(root, good)) {
                check(p.syntaxValid(), "Patch valide rejeté : " + p.files().get(0).parseErrors());
            }
            var bad = List.of(new PatchStager.Edit("src/main/java/demo/A.java", "return l.size();", "return l.size()"));
            try (var p = new PatchStager().prepare(root, bad)) {
                check(!p.syntaxValid() && !p.files().get(0).parseErrors().isEmpty(), "Patch invalide accepté");
            }
        } finally { deleteTree(root); }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
