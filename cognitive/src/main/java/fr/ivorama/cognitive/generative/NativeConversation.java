package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import java.util.*;
import java.util.function.Consumer;

/** Native text only: no IV instructions, tools, verifier, repair or answer transformation. */
public final class NativeConversation {
    private final GenerationBackend backend;
    private final int maximumMessages;
    private final List<ChatMessage> history = new ArrayList<>();
    /** Default native session keeps complete caller history until reset. */
    public NativeConversation(GenerationBackend backend) { this(backend,0); }
    /** A bounded window is an explicit API choice, never the CLI native default. */
    public NativeConversation(GenerationBackend backend, int maximumMessages) {
        this.backend = Objects.requireNonNull(backend);
        if (maximumMessages != 0 && (maximumMessages < 4 || maximumMessages > 64)) throw new IllegalArgumentException("Mémoire hors limites");
        this.maximumMessages = maximumMessages;
    }
    public static NativeConversation ollama(LocalLlmConfig config) {
        return new NativeConversation(OllamaGenerationBackend.nativeText(config));
    }
    public synchronized GenerationBackend.Result ask(String input, Consumer<String> partial) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("Demande requise");
        Objects.requireNonNull(partial);
        var messages = new ArrayList<>(history);
        messages.add(UserMessage.from(input));
        var result = backend.generate(List.copyOf(messages), partial);
        // Retain the exact native answer, including a token-limit interruption. No automatic retry.
        history.add(UserMessage.from(input));
        history.add(AiMessage.from(result.text()));
        while (maximumMessages > 0 && history.size() > maximumMessages) { history.removeFirst(); history.removeFirst(); }
        return result;
    }
    public synchronized void reset() { history.clear(); }
    public synchronized List<ChatMessage> history() { return List.copyOf(history); }
}
