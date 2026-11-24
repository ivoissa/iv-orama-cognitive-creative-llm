package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;

/** Entire FCI cycle and simulated provider contract, no Ollama/Qwen required. */
final class P23RoutingIntegrationTest {
    @Test void optionalRoutingPreservesAllBaselineDecisions() {
        P23RoutingProbe.run();
    }
}
