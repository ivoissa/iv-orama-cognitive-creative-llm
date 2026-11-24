package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Preflight of Ollama's configured context; never silently truncates documentary evidence. */
final class PromptBudget {
    private PromptBudget() { }
    static List<ChatMessage> prepare(List<ChatMessage> messages, LocalLlmConfig config) {
        Objects.requireNonNull(messages);
        if (messages.isEmpty()) throw new IllegalArgumentException("Au moins un message est requis");
        int historicalPairs = 0;
        for (int i = 1; messages.getFirst() instanceof SystemMessage && i + 1 < messages.size() - 1; i += 2) {
            if (!(messages.get(i) instanceof UserMessage) || !(messages.get(i + 1) instanceof AiMessage)) break;
            historicalPairs++;
        }
        // Hold output tokens and a fixed reserve for framing/tokenizer variance.
        int capacity = config.contextTokens() - config.outputTokens() - 128;
        if (capacity < 1) throw new IllegalArgumentException("Contexte trop petit pour la sortie configurée");
        var costs = new ArrayList<Integer>(messages.size());
        for (ChatMessage m : messages) {
            String content = switch (m) {
                case SystemMessage s -> s.text();
                case UserMessage u -> u.singleText();
                case AiMessage a -> a.text();
                default -> throw new IllegalArgumentException("Seuls les messages textuels système/utilisateur/assistant sont admis");
            };
            // Deliberately pessimistic proxy, not a claim about Qwen tokenizer exactness.
            costs.add(16 + (content.getBytes(StandardCharsets.UTF_8).length + 1) / 2);
        }
        var selected = PromptBudgetPolicy.select(costs, historicalPairs, capacity);
        var accepted = new ArrayList<ChatMessage>(selected.size());
        for (int index : selected) accepted.add(messages.get(index));
        return List.copyOf(accepted);
    }
}
