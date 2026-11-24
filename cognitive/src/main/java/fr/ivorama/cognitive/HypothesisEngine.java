package fr.ivorama.cognitive;

import java.util.*;

/** Prototype indépendant de validation : hypothèses explicites et vérifiables. */
public final class HypothesisEngine {
    public enum Status { POSSIBLE, CONDITIONAL, CONFIRMED, REFUTED }
    public record Hypothesis(String id, Set<String> required, Set<String> discriminants,
                             Set<String> contradictions, boolean conditional) {
        public Hypothesis {
            required = Set.copyOf(required);
            discriminants = Set.copyOf(discriminants);
            contradictions = Set.copyOf(contradictions);
        }
    }
    public record Assessment(String id, Status status, List<String> missingEvidence) {}
    public static Assessment assess(Hypothesis h, Set<String> observations) {
        if (!Collections.disjoint(h.contradictions(), observations))
            return new Assessment(h.id(), Status.REFUTED, List.of());
        var needed = new TreeSet<>(h.required());
        needed.addAll(h.discriminants());
        needed.removeAll(observations);
        if (!observations.containsAll(h.required()))
            return new Assessment(h.id(), Status.POSSIBLE, List.copyOf(needed));
        if (!observations.containsAll(h.discriminants()))
            return new Assessment(h.id(), h.conditional() ? Status.CONDITIONAL : Status.POSSIBLE, List.copyOf(needed));
        return new Assessment(h.id(), h.conditional() ? Status.CONDITIONAL : Status.CONFIRMED, List.of());
    }
    public static void main(String[] args) {
        int count = 0;
        // T1 : diagnostic HTTP : un 500 ne suffit pas à établir la cause SQL.
        var sql = new Hypothesis("HTTP500_CAUSE_UNIQUE_VIOLATION", Set.of("HTTP_500"),
                Set.of("SQLSTATE_23505", "CONSTRAINT_UNIQUE_IDENTIFIED"), Set.of("DB_NOT_CALLED"), false);
        check(assess(sql, Set.of("HTTP_500")).status() == Status.POSSIBLE, "T1a: pas de certitude depuis HTTP500"); count++;
        check(assess(sql, Set.of("HTTP_500", "SQLSTATE_23505")).status() == Status.POSSIBLE, "T1b: contrainte pas identifiee"); count++;
        check(assess(sql, Set.of("HTTP_500", "SQLSTATE_23505", "CONSTRAINT_UNIQUE_IDENTIFIED")).status() == Status.CONFIRMED, "T1c: preuve directe"); count++;
        check(assess(sql, Set.of("HTTP_500", "DB_NOT_CALLED")).status() == Status.REFUTED, "T1d: contradiction"); count++;
        // T2 : math : 3 points compatibles avec un polynome n'etablissent pas la loi universelle.
        var quadratic = new Hypothesis("SEQUENCE_IS_QUADRATIC", Set.of("DIFFERENCES_SECOND_ORDER_CONSTANT_ON_SAMPLE"),
                Set.of("GLOBAL_LAW_PROVEN"), Set.of("COUNTEREXAMPLE"), false);
        check(assess(quadratic, Set.of("DIFFERENCES_SECOND_ORDER_CONSTANT_ON_SAMPLE")).status() == Status.POSSIBLE, "T2a: extrapolation non prouvee"); count++;
        check(assess(quadratic, Set.of("DIFFERENCES_SECOND_ORDER_CONSTANT_ON_SAMPLE", "COUNTEREXAMPLE")).status() == Status.REFUTED, "T2b: contre-exemple"); count++;
        check(assess(quadratic, Set.of("DIFFERENCES_SECOND_ORDER_CONSTANT_ON_SAMPLE", "GLOBAL_LAW_PROVEN")).status() == Status.CONFIRMED, "T2c: preuve formelle attestee en entree"); count++;
        // T3 : Java : CME peut venir de plusieurs causes; stack trace seule insuffisante.
        var ownMutation = new Hypothesis("CME_SAME_THREAD_MUTATION", Set.of("CME"),
                Set.of("MODIFICATION_WITHIN_ITERATION_PROVEN"), Set.of("NO_SAME_THREAD_MUTATION"), false);
        var concurrent = new Hypothesis("CME_OTHER_THREAD_MUTATION", Set.of("CME"),
                Set.of("OTHER_THREAD_MUTATION_PROVEN"), Set.of("SINGLE_THREAD_EXECUTION_PROVEN"), false);
        check(assess(ownMutation, Set.of("CME")).status() == Status.POSSIBLE, "T3a: stacktrace non discriminante"); count++;
        check(assess(concurrent, Set.of("CME")).status() == Status.POSSIBLE, "T3b: cause concurrente non prouvee"); count++;
        check(assess(ownMutation, Set.of("CME", "MODIFICATION_WITHIN_ITERATION_PROVEN")).status() == Status.CONFIRMED, "T3c: modification observee"); count++;
        check(assess(concurrent, Set.of("CME", "SINGLE_THREAD_EXECUTION_PROVEN")).status() == Status.REFUTED, "T3d: hypothesis concurrente rejetee"); count++;
        // Conditions : meme une observation de ses discriminants ne constitue pas la preuve d'une implication universelle.
        var conditional = new Hypothesis("IF_A_THEN_B", Set.of("ASSUMPTION_A"), Set.of("DERIVATION_CHECKED"), Set.of("INFERENCE_INVALID"), true);
        check(assess(conditional, Set.of("ASSUMPTION_A", "DERIVATION_CHECKED")).status() == Status.CONDITIONAL, "Condition conservee"); count++;
        System.out.printf("PASS: %d/12 assertions, 3 domaines et 1 garde conditionnelle.%n", count);
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
}
