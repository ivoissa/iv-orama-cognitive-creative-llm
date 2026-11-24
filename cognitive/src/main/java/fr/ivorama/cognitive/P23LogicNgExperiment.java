package fr.ivorama.cognitive;

import java.util.Objects;

/**
 * Explicit P2.3b production-classpath adapter: runs the REAL LogicNG explanation
 * service, with independently configured solver limits, only when requested.
 * It does not install a default provider or change an FCI/RDO verdict.
 * Requires the LogicNG Maven dependency already declared in pom.xml.
 */
public final class P23LogicNgExperiment {
    private P23LogicNgExperiment() {}

    public static P23RoutingTelemetry.Measurement measure(
            IntegratedQuestionCycle cycle, String questionId,
            P23ProofRouting.Options routingBudget,
            LogicNgExplanationService.Limits solverBudget) {
        Objects.requireNonNull(solverBudget);
        return P23RoutingTelemetry.measure(cycle, questionId,
                new LogicNgExplanationService(solverBudget), routingBudget);
    }
}
