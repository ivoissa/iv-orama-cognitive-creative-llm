package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.StructuredJsonWriter;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic regression matrix, not an independent or blind scientific corpus. */
public class FormalRegressionMatrixTest {
    private record Scenario(String source, String expected) { }

    private static List<Scenario> scenarios() {
        var cases = new ArrayList<Scenario>();
        for (String noun : List.of("module", "relais", "capteur")) {
            for (String id : List.of("z3", "k7", "b9")) {
                for (String property : List.of("actif", "stable", "prêt")) {
                    String subject = "Le " + noun + " " + id;
                    String fact = subject + " est " + property + ".";
                    String negative = subject + " n'est pas " + property + ".";
                    String question = subject + " est-il " + property + " ?";
                    String other = property.equals("actif") ? "stable" : "actif";
                    cases.add(new Scenario(fact + "\n" + question, "TRUE_CONDITIONALLY"));
                    cases.add(new Scenario(negative + "\n" + question, "FALSE_CONDITIONALLY"));
                    cases.add(new Scenario(subject + " est " + other + ".\n" + question, "UNDETERMINED"));
                    cases.add(new Scenario(fact + "\n" + negative + "\n" + question, "INCONSISTENT"));
                }
            }
        }
        return cases;
    }

    @Test void knownVerdictsAndProofsArePreservedAcross324ListVariants() {
        for (var scenario : scenarios()) {
            var plain = new FormalReasoningBridge().tryAutomatic(scenario.source()).orElseThrow();
            assertEquals(scenario.expected(), plain.receipt().status(), scenario.source());
            for (String marker : List.of("- ", "* ", "• ")) {
                String list = marker + scenario.source().replace("\n", "\n" + marker);
                var decorated = new FormalReasoningBridge().tryAutomatic(list).orElseThrow();
                assertEquals(plain.receipt().status(), decorated.receipt().status(), list);
                assertEquals(plain.receipt().proofChecked(), decorated.receipt().proofChecked(), list);
            }
        }
    }

    /** Run against pre-change or current classes to retain complete paired Java responses.
     * java -cp "target/test-classes:target/classes:target/lib/*" \
     * fr.ivorama.cognitive.generative.FormalRegressionMatrixTest /tmp/matrix.jsonl
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Fichier JSONL de sortie requis");
        var rows = new ArrayList<String>();
        int id = 0;
        for (var scenario : scenarios()) {
            var answer = new FormalReasoningBridge().tryAutomatic(scenario.source()).orElseThrow();
            int recognized = 0;
            for (String marker : List.of("- ", "* ", "• ")) {
                if (AutomaticFormalRouter.recognize(marker + scenario.source().replace("\n", "\n" + marker)).isPresent()) recognized++;
            }
            rows.add(StructuredJsonWriter.json(Map.of("id", id++, "source", scenario.source(),
                    "expected", scenario.expected(), "status", answer.receipt().status(),
                    "proofChecked", answer.receipt().proofChecked(), "text", answer.text(),
                    "listVariantsRecognized", recognized)));
        }
        Files.write(Path.of(args[0]), rows);
    }
}
