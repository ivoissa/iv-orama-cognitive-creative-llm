package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import fr.ivorama.cognitive.ModelExplorer;
import fr.ivorama.cognitive.P31CandidateAnswerAudit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * P3.2 (OBSERVE, opt-in): provider-agnostic, read-only audit of one model turn.
 * It does not infer reference facts from the prompt, history, model output or a provider.
 * Generated text, token metadata, streaming chunks and provider failures are unchanged.
 * No network calls or vendor implementation are required by this adapter.
 */
public final class ObservedGenerationBackend implements GenerationBackend {
    public enum AuditState { ASSESSED, TRUNCATED, UNCONFIRMED_FINISH, AUDIT_FAILED }

    public record Observation(Result generation, AuditState state,
                              Optional<P31CandidateAnswerAudit.Review> review,
                              long auditNanos, String limitation) {
        public Observation {
            Objects.requireNonNull(generation);
            Objects.requireNonNull(state);
            Objects.requireNonNull(review);
            Objects.requireNonNull(limitation);
            if (auditNanos < 0) throw new IllegalArgumentException("Invalid audit time");
            if (state == AuditState.AUDIT_FAILED && review.isPresent())
                throw new IllegalArgumentException("Failed audit cannot carry a successful review");
        }
        /** Only a complete, completely interpreted and entailed draft is supported. */
        public boolean fullySupported() {
            return state == AuditState.ASSESSED && review.isPresent()
                    && review.orElseThrow().isFullySupported();
        }
    }

    private final GenerationBackend delegate;
    private final String declaredReference;
    private final ModelExplorer.Budget budget;
    private final Consumer<Observation> sink;

    /** Declared reference must come from a separately admitted source, not generated text. */
    public ObservedGenerationBackend(GenerationBackend delegate, String declaredReference,
                                     ModelExplorer.Budget budget, Consumer<Observation> sink) {
        this.delegate = Objects.requireNonNull(delegate);
        this.declaredReference = Objects.requireNonNull(declaredReference);
        this.budget = Objects.requireNonNull(budget);
        this.sink = Objects.requireNonNull(sink);
        if (declaredReference.isBlank() || declaredReference.length() > 6000)
            throw new IllegalArgumentException("Declared reference is empty or too long");
    }

    @Override
    public Result generate(List<ChatMessage> messages, Consumer<String> partial) {
        // Do not catch provider failures: the original generation semantics must survive.
        Result result = delegate.generate(messages, partial);
        long start = System.nanoTime();
        Observation observed;
        try {
            String label = result.model();
            if (label == null || label.isBlank()) label = "unknown-provider";
            if (label.length() > 128) label = label.substring(0, 128);
            var review = P31CandidateAnswerAudit.inspect(label, declaredReference, result.text(), budget);
            AuditState state = result.truncated() ? AuditState.TRUNCATED
                    : confirmedFinish(result.finishReason()) ? AuditState.ASSESSED
                    : AuditState.UNCONFIRMED_FINISH;
            observed = new Observation(result, state, Optional.of(review),
                    elapsedSince(start), state == AuditState.TRUNCATED ? "Model output truncated; no whole-answer certification"
                    : state == AuditState.UNCONFIRMED_FINISH ? "Unconfirmed generation completion; no whole-answer certification"
                    : review.limitation());
        } catch (RuntimeException failure) {
            observed = new Observation(result, AuditState.AUDIT_FAILED, Optional.empty(),
                    elapsedSince(start), "Audit failed (" + failure.getClass().getSimpleName()
                    + "); the generated answer was not changed or certified");
        }
        // OBSERVE never makes an otherwise successful generation fail because an observer is broken.
        // Nothing is logged or stored unless the explicit caller-supplied sink does so.
        try { sink.accept(observed); }
        catch (RuntimeException ignoredObserverFailure) { /* original result remains authoritative */ }
        return result;
    }

    private static boolean confirmedFinish(String reason) {
        return "STOP".equalsIgnoreCase(reason) || "END_TURN".equalsIgnoreCase(reason);
    }
    private static long elapsedSince(long start) {
        return Math.max(0L, System.nanoTime() - start);
    }
}
