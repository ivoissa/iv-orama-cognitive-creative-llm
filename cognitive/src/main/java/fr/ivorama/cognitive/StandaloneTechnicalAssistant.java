package fr.ivorama.cognitive;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Scanner;

/**
 * IVOC-TASK-001. Offline, deterministic, intentionally closed task interface.
 * FCI/RDO is used for diagnostic questions; explanations/code are explicitly
 * prevalidated recipes, not an open-ended generative model.
 * No HTTP calls, external LLM, learned parameters, or executing user code.
 */
public final class StandaloneTechnicalAssistant {
    private final IntegratedQuestionCycle reasoning = new IntegratedQuestionCycle();
    private final TaskDialogue taskDialogue = new TaskDialogue();
    private final HypothesisDialogue hypothesisDialogue = new HypothesisDialogue();
    private String rootCauseQuestionId;
    private String logQuestionId;
    private String symptomQuestionId;
    private boolean diagnosticOpened;
    private boolean http500Observed;
    private boolean sql23505Observed;
    private int dialogueTurn;

    public record Response(String text, String kind, boolean questionStillOpen) {}

    public Response reply(String input) {
        if (input == null || input.isBlank()) return new Response(
                "Précise ta question ou indique les faits observés.", "CLARIFICATION", true);
        dialogueTurn++;
        if (HypothesisDialogue.isCommand(input)) return hypothesisDialogue.reply(input);
        if (TaskDialogue.isTaskCommand(input)) return taskDialogue.reply(input);
        String s = normalize(input);
        if ((s.startsWith("log:") || s.startsWith("logs:") || s.startsWith("journal:"))
                && (s.contains("sqlstate 23505") || s.contains("sqlstate=23505"))
                && !s.contains("pas de sqlstate") && !s.contains("sans sqlstate")
                && !s.contains("aucun sqlstate")) {
            ensureDiagnostic();
            if (!sql23505Observed) {
                sql23505Observed = true;
                reasoning.teach(Formula.atom("sqlstate23505_reported"),
                        "journal déclaré par l'utilisateur, tour " + dialogueTurn);
            }
            IntegratedQuestionCycle.Report fact = reasoning.question(logQuestionId);
            IntegratedQuestionCycle.Report cause = reasoning.question(rootCauseQuestionId);
            return new Response("Journal reçu : SQLSTATE 23505 (PostgreSQL : violation d'unicité). "
                    + "L'information est rapportée, non vérifiée indépendamment.\n"
                    + "FCI — présence de ce code dans le journal : " + status(fact)
                    + "; cause racine définitivement prouvée : " + status(cause) + ".\n"
                    + "RDO — examiner le nom de la contrainte, l'INSERT/UPDATE concerné "
                    + "et les paramètres de la requête ; vérifier si la valeur existe déjà. "
                    + "Ne pas conclure qu'un HTTP 500 prouve à lui seul une panne SQL.",
                    "DIAGNOSTIC_EVIDENCE", true);
        }
        if (s.contains("500") && (s.contains("http") || s.contains("erreur") || s.contains("api"))) {
            ensureDiagnostic();
            if (!http500Observed) {
                http500Observed = true;
                reasoning.teach(Formula.atom("http500_reported"),
                        "symptôme déclaré par l'utilisateur, tour " + dialogueTurn);
            }
            var symptom = reasoning.question(symptomQuestionId);
            var cause = reasoning.question(rootCauseQuestionId);
            return new Response("Je note le symptôme HTTP 500 (information déclarée).\n"
                    + "FCI — symptôme connu : " + status(symptom)
                    + "; cause racine : " + status(cause) + ".\n"
                    + "RDO — fournis une ligne d'erreur des logs, l'opération API "
                    + "et l'horodatage. Sans ces éléments, je ne peux pas attribuer l'anomalie "
                    + "à la base, à l'application ou à un service distant.",
                    "DIAGNOSTIC_QUESTION", true);
        }
        if (s.contains("jpa") && s.contains("hibernate") &&
                (s.contains("expli") || s.contains("difference") || s.contains("différence"))) {
            return new Response("JPA est une spécification Java : elle définit des contrats "
                    + "de persistance (par exemple EntityManager). Hibernate est une "
                    + "implémentation ORM qui peut fournir ces contrats.\n"
                    + "Image simple : JPA décrit les règles du jeu ; Hibernate est l'un des moteurs "
                    + "qui les appliquent.\n"
                    + "Exemple : @Entity est une annotation JPA ; Hibernate peut traduire "
                    + "une opération persist() en commandes SQL.\n"
                    + "Limite : le SQL exact dépend du mapping, du dialecte et du contexte "
                    + "transactionnel ; je n'en déduis aucun sans données.",
                    "EXPLICATION_VERIFIEE", false);
        }
        if (s.contains("java") && (s.contains("code") || s.contains("methode") || s.contains("méthode")) &&
                (s.contains("chaines non vides") || s.contains("chaînes non vides") ||
                        s.contains("chaines non vide") || s.contains("chaînes non vide"))) {
            return new Response("Java 17 — compter les chaînes non nulles et non blanches :\n"
                    + "```java\n" + codeSnippet() + "\n```\n"
                    + "Contrat : liste null => NullPointerException ; éléments null ignorés ; "
                    + "blancs ignorés via isBlank() ; complexité O(n). "
                    + "C'est une recette Java contrôlée, pas la génération arbitraire de programmes.",
                    "CODE_CONTROLE", false);
        }
        return new Response("Je ne sais pas encore traiter cette demande librement. "
                + "Périmètre actuel : enquête par hypothèses (« aide enquete »), diagnostic HTTP 500 / SQLSTATE 23505, "
                + "explication JPA-Hibernate, recette Java 17, et planification de tâches via « aide tache ». "
                + "Précise le sujet et les faits ; aucune réponse n'est inventée.",
                "HORS_PERIMETRE", true);
    }

    public boolean rootCauseOpen() {
        return diagnosticOpened && reasoning.question(rootCauseQuestionId).answer().isEmpty();
    }

    public static String codeSnippet() {
        return "public static long compterChainesNonVides(java.util.List<String> valeurs) {\n"
                + "    java.util.Objects.requireNonNull(valeurs, \"valeurs\");\n"
                + "    return valeurs.stream().filter(s -> s != null && !s.isBlank()).count();\n"
                + "}";
    }

    public static void run(InputStream in, PrintStream out) {
        StandaloneTechnicalAssistant session = new StandaloneTechnicalAssistant();
        out.println("IV-ORAMA 0.6.17 — assistant technique hors ligne (périmètre contrôlé)");
        out.println("Saisir 'quitter' pour terminer.");
        Scanner scanner = new Scanner(in, StandardCharsets.UTF_8);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line.trim().equalsIgnoreCase("quitter")) break;
            Response response = session.reply(line);
            out.println("[" + response.kind + "] " + response.text);
        }
    }

    /** A persistent question id binds all dialogue turns to one FCI obligation. */
    private void ensureDiagnostic() {
        if (diagnosticOpened) return;
        diagnosticOpened = true;
        rootCauseQuestionId = reasoning.ask("root_cause_confirmed").id();
        symptomQuestionId = reasoning.ask("http500_reported").id();
        reasoning.registerObservable("sqlstate23505_reported", 1);
        logQuestionId = reasoning.ask("sqlstate23505_reported").id();
    }

    public String diagnosticAudit() {
        if (!diagnosticOpened) return "Aucune investigation ouverte.";
        return "Questions FCI persistantes : " + rootCauseQuestionId + "="
                + status(reasoning.question(rootCauseQuestionId)) + ", " + logQuestionId + "="
                + status(reasoning.question(logQuestionId)) + "; événements=" + reasoning.journal().size();
    }

    private static String status(IntegratedQuestionCycle.Report report) {
        if (report.status() == IntegratedQuestionCycle.Status.INCONSISTENT) return "INCONSISTENT";
        if (report.status() == IntegratedQuestionCycle.Status.RESOURCE_LIMIT) return "RESOURCE_LIMIT";
        return report.answer().map(a -> a ? "YES" : "NO").orElse("UNKNOWN");
    }
    private static String normalize(String s) {
        return java.text.Normalizer.normalize(s.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFC)
                .replaceAll("\\s+", " ").trim();
    }
}
