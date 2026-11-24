package fr.ivorama.cognitive;

import java.io.IOException;
import java.io.InputStream;

/** Point d'entrée explicite pour essais manuels dans IntelliJ (Java 17+). */
public final class IVOramaInteractiveMain {
    private IVOramaInteractiveMain() {}

    public static void main(String[] args) {
        if (args.length > 0) { launch(args[0]); return; }
        System.out.println("=== IV-ORAMA Cognitive — console de tests ===");
        System.out.println("1 - Assistant technique et enquête par hypothèses (0.6.17)");
        System.out.println("2 - Questions en français contrôlé");
        System.out.println("3 - Raisonnement logique FCI / RDO");
        System.out.println("4 - Questions et graphe ontologique 0.6.19");
        System.out.print("Mode [1] : ");
        System.out.flush();
        // Ne pas fermer System.in : la console choisie réutilise le même flux.
        try { launch(readSingleLine(System.in)); }
        catch (IOException e) { System.err.println("Erreur de saisie : " + e.getMessage()); }
    }

    // Ne lit jamais au-delà du premier retour ligne : évite de consommer les questions
    // déjà entrées après le choix du mode dans System.in.
    private static String readSingleLine(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        int c;
        while ((c = in.read()) != -1 && c != '\n') {
            if (c != '\r' && s.length() < 32) s.append((char)c);
        }
        return s.toString().trim();
    }

    private static void launch(String choice) {
        switch (choice) {
            case "", "1" -> StandaloneTechnicalAssistant.run(System.in, System.out);
            case "2" -> FrenchConsole.run(System.in, System.out);
            case "3" -> DialogueConsole.run(System.in, System.out);
            case "4" -> OntologicalConsole.run(System.in, System.out);
            default -> System.out.println("Mode inconnu. Relance la classe et choisis 1, 2, 3 ou 4.");
        }
    }
}
