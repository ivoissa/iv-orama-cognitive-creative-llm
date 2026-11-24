package fr.ivorama.cognitive;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** FND-004 : dialogue logique à plusieurs tours, sans traitement libre du français. */
public final class DialogueConsole {
    private DialogueConsole() {}

    public static void run(InputStream input, PrintStream output) {
        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
        output.println("IV-ORAMA Cognitive 0.4.3 — cycle interrogatif intégré (logique contrôlée)");
        output.println("aide | sais <formule> | observable <formule> | ? <formule> | observe Q1 oui/non | ignore Q1 | questions | retire P1 | liste | quitter");
        try (Scanner scanner = new Scanner(input, StandardCharsets.UTF_8)) {
            while (true) {
                output.print("iv-orama> "); output.flush();
                if (!scanner.hasNextLine()) break;
                String line = scanner.nextLine().trim();
                if (line.isBlank()) continue;
                if (line.equalsIgnoreCase("quitter") || line.equalsIgnoreCase("exit")) break;
                try {
                    if (line.equalsIgnoreCase("aide")) {
                        output.println("sais p -> q : assertion admise conditionnellement (non vérifiée)");
                        output.println("observable p : rend p admissible comme cible d'une demande RDO");
                        output.println("? q : pose une question et crée une obligation identifiée");
                        output.println("observe Q1 oui/non : déclaration utilisateur sur l'observation demandée");
                        output.println("ignore Q1 : observation indisponible, ne pas la redemander pour Q1");
                        output.println("questions : états de toutes les questions; retire P1 : révise les justifications");
                        output.println("Formules : p, non p, p et q, p -> q, (p et q) -> r");
                    } else if (line.startsWith("sais ")) {
                        String id = cycle.teach(line.substring(5), "assertion utilisateur non vérifiée");
                        output.println("Prémisse " + id + " admise conditionnellement.");
                    } else if (line.startsWith("observable ")) {
                        cycle.registerObservable(line.substring(11), 1);
                        output.println("Observable enregistré (déclaration seulement ; aucun capteur).");
                    } else if (line.startsWith("? ")) {
                        show(cycle.ask(line.substring(2)), output);
                    } else if (line.startsWith("observe ")) {
                        String[] parts = line.substring(8).trim().split("\\s+");
                        if (parts.length != 2 || !(parts[1].equalsIgnoreCase("oui") || parts[1].equalsIgnoreCase("non")))
                            throw new IllegalArgumentException("Usage : observe Q1 oui ou observe Q1 non");
                        show(cycle.answerObservation(parts[0], parts[1].equalsIgnoreCase("oui"),
                                "réponse utilisateur à la demande RDO"), output);
                    } else if (line.startsWith("ignore ")) {
                        show(cycle.declineObservation(line.substring(7).trim()), output);
                    } else if (line.startsWith("retire ")) {
                        cycle.retract(line.substring(7).trim());
                        output.println("Prémisse retirée ; les questions existantes ont été réévaluées.");
                    } else if (line.equals("questions")) {
                        if (cycle.questions().isEmpty()) output.println("Aucune question posée : stabilité interrogative.");
                        for (var q : cycle.questions()) show(q, output);
                    } else if (line.equals("liste")) {
                        for (var p : cycle.premises())
                            output.println(p.id() + " " + (p.active() ? "ACTIVE" : "RETIRÉE")
                                    + " : " + p.formula() + " [" + p.kind() + "]");
                    } else output.println("Commande non reconnue. Tape aide.");
                } catch (IllegalArgumentException | IllegalStateException ex) {
                    output.println("Erreur : " + ex.getMessage());
                }
            }
        }
    }

    private static void show(IntegratedQuestionCycle.Report report, PrintStream out) {
        out.println(report.id() + " — " + report.status() + (report.reopened() ? " (faille rouverte)" : ""));
        switch (report.status()) {
            case RESTORED_FORMALLY -> out.println(report.answer().orElseThrow()
                    ? "Oui : théorème formel prouvé sans prémisse externe."
                    : "Non : négation formellement prouvée sans prémisse externe.");
            case RESTORED_CONDITIONALLY, RESTORED_JUSTIFIED -> out.println(report.answer().orElseThrow()
                    ? "Oui, sous les prémisses admises (non certifiées empiriquement)."
                    : "Non, sous les prémisses admises (non certifiées empiriquement).");
            case OPEN -> out.println("Je ne peux pas déterminer la réponse avec les prémisses actuelles.");
            case NEED_INFORMATION -> out.println("Je ne peux pas déterminer la réponse. Peux-tu observer : "
                    + report.requestedObservation().orElseThrow() + " ? Réponds : observe " + report.id() + " oui/non");
            case INCONSISTENT -> out.println("Impossible de conclure : les prémisses sont contradictoires.");
            case RESOURCE_LIMIT -> out.println("Impossible de conclure : budget de calcul atteint.");
        }
        out.println("Preuve/support : " + report.support());
        report.certificate().ifPresent(cert -> {
            out.println("Certificat : " + (cert.proofChecked() ? "preuve vérifiée" : "non produit")
                    + ", version K=" + cert.version());
            for (var dep : cert.dependencies()) {
                out.println("  Prémisse " + dep.formula() + " (sources alternatives déclarées) :");
                for (var source : dep.alternativeActiveSources())
                    out.println("    " + source.id() + " [" + source.kind() + "] "
                            + source.claimedSource() + " — NON CERTIFIÉE");
            }
        });
        out.println("Justification : " + report.explanation());
        if (report.questionGainBits().isPresent())
            out.printf(java.util.Locale.ROOT, "Progrès relatif à Q : %.3f bits (pas un score de vérité)%n",
                    report.questionGainBits().orElseThrow());
    }

    public static void main(String[] args) { run(System.in, System.out); }
}
