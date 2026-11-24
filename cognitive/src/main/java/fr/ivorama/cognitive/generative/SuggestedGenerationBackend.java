package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import fr.ivorama.cognitive.ModelExplorer;
import fr.ivorama.cognitive.P32SuggestedCorrections;
import java.util.*;
import java.util.function.Consumer;

/**
 * P3.2b opt-in: provider-independent advisory layer wrapping OBSERVE.
 * Neither the generated text, token accounting, stream, nor knowledge base
 * is modified. Truncated and unconfirmed generations never get suggestions.
 */
public final class SuggestedGenerationBackend implements GenerationBackend {
    public record AdvisoryTurn(ObservedGenerationBackend.Observation observation,
                               Optional<P32SuggestedCorrections.Advice> advice,
                               String limitation) {
        public AdvisoryTurn {
            Objects.requireNonNull(observation); Objects.requireNonNull(advice);
            Objects.requireNonNull(limitation);
        }
    }
    private final ObservedGenerationBackend observed;
    public SuggestedGenerationBackend(GenerationBackend delegate, String declaredReference,
                                      ModelExplorer.Budget budget, Consumer<AdvisoryTurn> sink) {
        Objects.requireNonNull(budget); Objects.requireNonNull(sink);
        observed = new ObservedGenerationBackend(delegate, declaredReference, budget, turn -> {
            Optional<P32SuggestedCorrections.Advice> advice=Optional.empty();
            String limitation="Generation not confirmed complete or audit unavailable";
            if (turn.state()==ObservedGenerationBackend.AuditState.ASSESSED && turn.review().isPresent()) {
                try {
                    advice=Optional.of(P32SuggestedCorrections.propose(turn.review().orElseThrow(),budget));
                    limitation=advice.orElseThrow().limitation();
                } catch (RuntimeException failure) {
                    limitation="Advisory analysis failed; model answer unchanged";
                }
            }
            try { sink.accept(new AdvisoryTurn(turn,advice,limitation)); }
            catch (RuntimeException ignored) { /* observer never controls provider result */ }
        });
    }
    @Override public Result generate(List<ChatMessage> messages, Consumer<String> partial) {
        return observed.generate(messages, partial);
    }
}
