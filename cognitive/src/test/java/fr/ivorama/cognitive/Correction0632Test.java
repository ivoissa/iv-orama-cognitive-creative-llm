package fr.ivorama.cognitive;

import java.util.*;

/**
 * Régressions des corrections issues du test de 0.6.31 : actes directifs, pronoms, accord.
 * Aucune réponse n'est enregistrée : chaque cas vérifie une construction ou un invariant du journal.
 */
public final class Correction0632Test {
    private static int checks;
    private static void ok(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    private static long posed(IntegratedQuestionCycle cycle) {
        return cycle.journal().stream().filter(e -> e.operation().equals("QUESTION_POSED")).count();
    }

    private static void directives() {
        // Paraphrases d'une même demande : même acte, quelle que soit la construction.
        Map<String, DirectiveActs.Kind> cases = new LinkedHashMap<>();
        cases.put("Écris une phrase poétique sur la mer.", DirectiveActs.Kind.CREATE);
        cases.put("ecris une phrase poétique sur la mer", DirectiveActs.Kind.CREATE);
        cases.put("Compose une phrase poétique sur la mer.", DirectiveActs.Kind.CREATE);
        cases.put("Peux-tu rédiger une phrase poétique sur la mer ?", DirectiveActs.Kind.CREATE);
        cases.put("Pourriez-vous inventer une phrase poétique sur la mer ?", DirectiveActs.Kind.CREATE);
        cases.put("Est-ce que tu peux composer une phrase poétique ?", DirectiveActs.Kind.CREATE);
        cases.put("J'aimerais que tu écrives une phrase poétique sur la mer.", DirectiveActs.Kind.CREATE);
        cases.put("Je voudrais que vous composiez une phrase poétique.", DirectiveActs.Kind.CREATE);
        cases.put("S'il te plaît, invente une phrase poétique.", DirectiveActs.Kind.CREATE);
        cases.put("Explique ce passage.", DirectiveActs.Kind.EXPLAIN);
        cases.put("Analyse ce texte.", DirectiveActs.Kind.ANALYZE);
        cases.put("Résume ce paragraphe.", DirectiveActs.Kind.REFORMULATE);
        for (var e : cases.entrySet()) {
            var d = DirectiveActs.classify(e.getKey());
            ok(d.isPresent() && d.get().kind() == e.getValue(), "Acte attendu " + e.getValue() + " pour : " + e.getKey());
        }
        // Non directifs : déclaratifs, questions logiques, souhait personnel, verbe isolé.
        for (String s : List.of(
                "Le capteur c7 est stable. Est-ce que le capteur c7 est stable ?",
                "Est-ce que le capteur c7 est actif ?",
                "J'aimerais écrire un poème.",
                "Écris",
                "Compose est un mot.",
                "Qu'est-ce que la photosynthèse ?"))
            ok(DirectiveActs.classify(s).isEmpty(), "Faux acte directif : " + s);
        var mixed = DirectiveActs.classify("Écris un poème. Le capteur c7 est stable. Est-ce que le capteur c7 est stable ?");
        ok(mixed.isPresent() && mixed.get().mixed(), "Texte mixte signalé");
    }

    private static void routing() {
        var cycle = new IntegratedQuestionCycle();
        var router = new FrenchReasoningRouter(cycle, q -> { throw new AssertionError("Aucun appel réseau pour un acte directif"); });
        var turn = router.respond("Écris une phrase poétique sur la mer.");
        ok(turn.directive().isPresent() && turn.logical().isEmpty() && turn.documentary().isEmpty(), "Un seul mode : directif");
        ok(posed(cycle) == 0, "Un acte directif n'ouvre aucune question");
        ok(turn.execution().orElseThrow().status()==TextTaskService.Status.CREATED && turn.execution().orElseThrow().composition().orElseThrow().roundTripVerified(), "Évolution 0.6.33 : création réelle contrôlée, remplace l’ancien diagnostic d’indisponibilité");
        // Le chemin logique est inchangé et ouvre bien une question.
        var logical = router.respond("Le capteur c7 n'est pas stable. Si le capteur c7 est actif, alors le capteur c7 est stable. Est-ce que le capteur c7 est actif ?");
        ok(logical.logical().isPresent() && logical.logical().get().answer().status() == StructuredReasoningService.Status.FALSE_CONDITIONALLY, "Modus tollens conservé");
        ok(posed(cycle) == 1, "Une seule question ouverte pour la question logique");
    }

    private static StructuredReasoningService.Status status(String text) {
        return new FrenchStructuredReasoningService(new IntegratedQuestionCycle()).respond(text).answer().status();
    }

    private static void pronouns() {
        var turn = new FrenchStructuredReasoningService(new IntegratedQuestionCycle())
            .respond("Le capteur c7 est stable. Il est actif. Est-ce que le capteur c7 est actif ?");
        ok(turn.answer().status() == StructuredReasoningService.Status.INCOMPLETE_INTERPRETATION, "Pronom : jamais de conclusion");
        String msg = String.join(" ; ", turn.translation().unresolved());
        ok(msg.contains("Pronom « il »") && msg.contains("le capteur c7"), "Diagnostic de pronom précis : " + msg);
        ok(!msg.contains("déterminant hors fragment"), "Ancien message générique supprimé");
    }

    private static void agreement() {
        // Même entité, deux accords : rejeté explicitement, jamais deux propriétés indépendantes.
        ok(status("La lampe l9 est allumée. Est-ce que la lampe l9 est allumé ?") == StructuredReasoningService.Status.INCOMPLETE_INTERPRETATION, "Accord conflictuel rejeté");
        // Entités différentes : le même adjectif accordé différemment reste valide.
        ok(status("Le capteur c7 est chaud. La lampe l9 est chaude. Est-ce que le capteur c7 est chaud ?") == StructuredReasoningService.Status.TRUE_CONDITIONALLY, "Deux entités, deux accords valides");
        ok(status("La lampe l9 est allumée. Est-ce que la lampe l9 est allumée ?") == StructuredReasoningService.Status.TRUE_CONDITIONALLY, "Même forme conservée");
        ok(status("Le capteur c7 n'est pas stable. Est-ce que le capteur c7 est stable ?") == StructuredReasoningService.Status.FALSE_CONDITIONALLY, "Négation conservée");
    }

    public static void main(String[] args) {
        directives(); routing(); pronouns(); agreement();
        System.out.println("Correction0632Test OK : " + checks + " vérifications");
    }
}
