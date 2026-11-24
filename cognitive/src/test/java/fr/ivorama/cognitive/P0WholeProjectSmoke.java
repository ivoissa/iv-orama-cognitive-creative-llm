package fr.ivorama.cognitive;

/** Whole-project integration probe, NOT executed in the isolated attachment-only workspace.
 * Compile against the REAL IV-ORAMA project and run after its existing test suites.
 * No Ollama, Qwen or provider API is needed: this probes the deterministic reasoning core.
 */
public final class P0WholeProjectSmoke {
    private static int checks;
    private static void ensure(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static int runAll() {
        checks=0;
        long started = System.nanoTime();
        Formula a = PropositionalInput.parse("A");
        Formula b = PropositionalInput.parse("B");
        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();

        String pa = cycle.teach(a, "integration-test P1");
        cycle.teach(Formula.implies(a, b), "integration-test P2");
        IntegratedQuestionCycle.Report conditional = cycle.ask(b);
        ensure(conditional.status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "Modus ponens should conditionally restore Q");
        ensure(conditional.proofChecked(), "Modus ponens must have a checked proof");
        ensure(conditional.support() == IntegratedQuestionCycle.Support.PROOF_CONDITIONAL,
                "Modus ponens should have conditional support");
        ensure(conditional.certificate().isPresent() &&
                conditional.certificate().orElseThrow().dependencies().size() == 2,
                "Two active evidence groups must be referenced");

        IntegratedQuestionCycle.Report theorem = cycle.ask(Formula.implies(a, a));
        ensure(theorem.status() == IntegratedQuestionCycle.Status.RESTORED_FORMALLY,
                "Identity implication should have assumption-free proof");
        ensure(theorem.certificate().isPresent() &&
                theorem.certificate().orElseThrow().hasPremiseFreeFormalCertificate(),
                "Formal certificate should be independently checkable");

        cycle.retract(pa);
        IntegratedQuestionCycle.Report reopened = cycle.question(conditional.id());
        ensure(reopened.reopened(), "Question must reopen on retraction of a supporting premise");
        ensure(reopened.status() != IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY &&
                reopened.status() != IntegratedQuestionCycle.Status.RESTORED_FORMALLY,
                "Retracted proof cannot stay active");
        ensure(!cycle.isCertificateCurrent(conditional.id(),conditional.certificate().orElseThrow()),
                "Saved certificate must expire on knowledge revision");
        ensure(cycle.question(theorem.id()).status() == IntegratedQuestionCycle.Status.RESTORED_FORMALLY,
                "A theorem remains true after external premise retraction");

        var contradiction = new IntegratedQuestionCycle();
        contradiction.teach(a, "integration-test positive");
        contradiction.teach(Formula.not(a), "integration-test negative");
        var inconsistency = contradiction.ask(b);
        ensure(inconsistency.status() == IntegratedQuestionCycle.Status.INCONSISTENT,
                "Contradictory assumptions must not produce a certified answer");
        ensure(!inconsistency.proofChecked(), "Inconsistent answer cannot be marked checked");
        return checks;
    }
    public static void main(String[] args) { System.out.println("P0.1 INTEGRATION PASS - "+runAll()+" checks"); }
}
