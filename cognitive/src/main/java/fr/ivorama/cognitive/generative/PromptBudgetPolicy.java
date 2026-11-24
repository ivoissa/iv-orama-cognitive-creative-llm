package fr.ivorama.cognitive.generative;

import java.util.ArrayList;
import java.util.List;

/** Deterministic admission control. Estimates are NOT model-tokenizer counts. */
public final class PromptBudgetPolicy {
    private PromptBudgetPolicy() { }

    /**
     * The first item and everything after the historical dialogue pairs are protected.
     * Historical user/assistant pairs occupy indices 1..2*historyPairs.
     * Never split a pair, drop a source, or discard the current user's input.
     */
    public static List<Integer> select(List<Integer> estimatedCosts, int historyPairs, int promptCapacity) {
        if (estimatedCosts == null || estimatedCosts.isEmpty() || historyPairs < 0 ||
                1L + 2L * historyPairs > estimatedCosts.size() || promptCapacity < 1) {
            throw new IllegalArgumentException("Budget de contexte invalide");
        }
        long total = 0;
        for (Integer cost : estimatedCosts) {
            if (cost == null || cost < 1) throw new IllegalArgumentException("Coût de message invalide");
            total += cost;
        }
        int discarded = 0;
        while (total > promptCapacity && discarded < historyPairs) {
            total -= estimatedCosts.get(1 + 2 * discarded);
            total -= estimatedCosts.get(2 + 2 * discarded);
            discarded++;
        }
        if (total > promptCapacity) {
            throw new IllegalArgumentException("CONTEXTE_INSUFFISANT : demande, contraintes ou preuves non réductibles ; " +
                    "augmenter explicitement num_ctx ou réduire la demande (estimation non tokenizer)");
        }
        var indices = new ArrayList<Integer>();
        indices.add(0);
        for (int i = 1 + 2 * discarded; i < estimatedCosts.size(); i++) indices.add(i);
        return List.copyOf(indices);
    }
}
