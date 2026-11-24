package fr.ivorama.cognitive;

/** P0.2 differential red/green regression: no false claims of zero semantic support. */
public final class P02SemanticSupportProbe {
    public static void main(String[] args) {
        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
        Formula a = Formula.atom("a");
        cycle.teach(Formula.not(Formula.not(a)), "double-negation declared");
        var report = cycle.ask(a);
        if (report.support() != IntegratedQuestionCycle.Support.MODEL_ONLY_CONDITIONAL)
            throw new AssertionError("Expected a model-only entailment, got " + report.support());
        if (report.certificate().orElseThrow().alternativeSupports().minimalSupports().isEmpty())
            throw new AssertionError("Expected a semantic minimal support");
        if (report.explanation().contains("Appuis : 0 groupe"))
            throw new AssertionError("False zero-source claim: " + report.explanation());
        System.out.println("P0.2 RED/GREEN smoke passed: " + report.explanation());
    }
}
