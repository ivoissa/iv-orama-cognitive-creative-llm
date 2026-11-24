package fr.ivorama.cognitive;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Three different tasks, plus anti-hallucination / code compilation checks. */
public final class StandaloneAssistantTest {
    private static int checks;
    private static void verify(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        var a = new StandaloneTechnicalAssistant();
        var q1 = "J'ai une erreur HTTP 500 sur mon API lors d'une modification de métadonnée.";
        var r1 = a.reply(q1);
        verify(r1.kind().equals("DIAGNOSTIC_QUESTION"), "open diagnostic");
        verify(r1.text().contains("cause racine : UNKNOWN"), "uncertainty");
        verify(r1.text().contains("fournis une ligne d'erreur"), "targeted RDO question");
        verify(a.rootCauseOpen(), "FCI gap remains open");
        verify(!r1.text().contains("violation d'unicité"), "no root cause invention");
        var mention = a.reply("Le manuel mentionne SQLSTATE 23505.");
        verify(mention.kind().equals("HORS_PERIMETRE"), "quoted manual is not evidence");
        verify(a.diagnosticAudit().contains("Q3=UNKNOWN"), "mere mention cannot close question");
        var denied = a.reply("Logs: pas de SQLSTATE 23505.");
        verify(denied.kind().equals("HORS_PERIMETRE"), "negative log not affirmative evidence");
        var evidence = a.reply("Logs: SQLSTATE 23505, duplicate key sur unique_user_id.");
        verify(evidence.kind().equals("DIAGNOSTIC_EVIDENCE"), "evidence mode");
        verify(evidence.text().contains("présence de ce code dans le journal : YES"), "observed log fact");
        verify(evidence.text().contains("cause racine définitivement prouvée : UNKNOWN"), "not confirmed");
        verify(a.rootCauseOpen(), "not silently closed");
        verify(a.diagnosticAudit().contains("Q1=UNKNOWN"), "persistent cause question");
        verify(a.diagnosticAudit().contains("Q3=YES"), "log question closed by premise");
        var repeated = a.reply("Logs: SQLSTATE 23505");
        verify(repeated.text().contains("YES"), "idempotent evidence");

        var b = new StandaloneTechnicalAssistant();
        var q2 = "Explique simplement la différence entre JPA et Hibernate.";
        var r2 = b.reply(q2);
        verify(r2.kind().equals("EXPLICATION_VERIFIEE"), "explanation route");
        verify(r2.text().contains("spécification") && r2.text().contains("implémentation"), "difference");
        verify(r2.text().contains("Limite"), "precise limits");
        verify(!r2.questionStillOpen(), "closed explanation");

        var c = new StandaloneTechnicalAssistant();
        var q3 = "Écris une méthode Java 17 pour compter les chaînes non vides.";
        var r3 = c.reply(q3);
        verify(r3.kind().equals("CODE_CONTROLE"), "code route");
        verify(r3.text().contains("Objects.requireNonNull"), "explicit null contract");
        verify(r3.text().contains("!s.isBlank()"), "whitespace filtering");
        verify(r3.text().contains("O(n)"), "complexity");
        verify(r3.text().contains("NullPointerException"), "documented contract matches code");
        var temp = Files.createTempDirectory("ivoc-task011-");
        var source = temp.resolve("GeneratedRecipe.java");
        try {
            Files.writeString(source, "public class GeneratedRecipe {\n" +
                    StandaloneTechnicalAssistant.codeSnippet() + "\n}\n", StandardCharsets.UTF_8);
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", "17", "-d", temp.toString(), source.toString());
            verify(result == 0, "generated Java compiles with JDK17 target");
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{temp.toUri().toURL()})) {
                var cls = loader.loadClass("GeneratedRecipe");
                var fn = cls.getMethod("compterChainesNonVides", List.class);
                verify(fn.invoke(null, java.util.Arrays.asList(null, "", "  ", "bonjour", "java"))
                        .equals(2L), "code actual execution");
                verify(fn.invoke(null, List.of("test", " ", " x ")).equals(2L), "another execution");
                boolean rejectedNull = false;
                try { fn.invoke(null, new Object[]{null}); }
                catch (java.lang.reflect.InvocationTargetException ex) {
                    rejectedNull = ex.getCause() instanceof NullPointerException;
                }
                verify(rejectedNull, "explicit null contract matches implementation");
            }
        } finally {
            try (var stream = Files.walk(temp)) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); }
                    catch (java.io.IOException ex) { throw new RuntimeException(ex); }
                });
            }
        }
        var unknown = c.reply("Donne un algorithme optimal pour tout problème NP-difficile.");
        verify(unknown.kind().equals("HORS_PERIMETRE"), "honest refusal");
        verify(unknown.questionStillOpen(), "unmet need preserved");
        verify(a.reply(" ").kind().equals("CLARIFICATION"), "blank input");

        System.out.println("=== TESTS REELS DES 3 QUESTIONS — IV-ORAMA ===");
        System.out.println("QUESTION 1 : " + q1);
        System.out.println("REPONSE MOTEUR : " + r1.text());
        System.out.println("PRECISION UTILISATEUR : Logs: SQLSTATE 23505, duplicate key sur unique_user_id.");
        System.out.println("REPONSE MOTEUR : " + evidence.text());
        System.out.println();
        System.out.println("QUESTION 2 : " + q2);
        System.out.println("REPONSE MOTEUR : " + r2.text());
        System.out.println();
        System.out.println("QUESTION 3 : " + q3);
        System.out.println("REPONSE MOTEUR : " + r3.text());
        System.out.println("CODE COMPILÉ JDK17 ET EXÉCUTÉ : OK");
        System.out.println("CHECKS " + checks + "/" + checks + " PASS");
    }
}
