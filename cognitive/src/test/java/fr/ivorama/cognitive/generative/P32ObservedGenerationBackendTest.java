package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.UserMessage;
import fr.ivorama.cognitive.ModelExplorer;
import fr.ivorama.cognitive.P31CandidateAnswerAudit;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Full local-llm-profile JUnit gate using ACTUAL LangChain4j message types, no Ollama. */
class P32ObservedGenerationBackendTest {
    private static final String REFERENCE = "La porte A est ouverte.";
    private static GenerationBackend scripted(String model, String text, String finish) {
        return (messages, partial) -> {
            partial.accept("La ");
            return new GenerationBackend.Result(text, model, finish, 1000, 10, 10, 5);
        };
    }
    @Test void sameOutputAndStreamingWithAndWithoutObserve() {
        var observations = new ArrayList<ObservedGenerationBackend.Observation>();
        var output = new ArrayList<String>();
        var backend = new ObservedGenerationBackend(scripted("any-model", "La porte A est ouverte.", "STOP"),
                REFERENCE, ModelExplorer.Budget.small(), observations::add);
        var conversation = new NativeConversation(backend);
        var answer = conversation.ask("test", output::add);
        assertEquals("La porte A est ouverte.", answer.text());
        assertEquals(List.of("La "), output);
        assertEquals(2, conversation.history().size());
        assertEquals(1, observations.size());
        assertSame(answer, observations.getFirst().generation());
        assertTrue(observations.getFirst().fullySupported());
    }
    @Test void acrossProvidersOnlyLogicalContentChangesTheVerdict() {
        for (String model : List.of("qwen", "claude", "gpt", "gemini", "other")) {
            var observations = new ArrayList<ObservedGenerationBackend.Observation>();
            GenerationBackend b = new ObservedGenerationBackend(scripted(model, "La porte A n'est pas ouverte.","STOP"),
                    REFERENCE, ModelExplorer.Budget.small(), observations::add);
            b.generate(List.of(UserMessage.from("test")), x -> { });
            assertEquals(P31CandidateAnswerAudit.Status.CONTRADICTED,
                    observations.getFirst().review().orElseThrow().status());
            assertFalse(observations.getFirst().fullySupported());
        }
    }
    @Test void failedProviderNeverBecomesFakeSuccess() {
        AtomicInteger delivered = new AtomicInteger();
        GenerationBackend failing = (messages, partial) -> { throw new IllegalStateException("provider down"); };
        var wrapper = new ObservedGenerationBackend(failing, REFERENCE, ModelExplorer.Budget.small(),
                observation -> delivered.incrementAndGet());
        assertEquals("provider down", assertThrows(IllegalStateException.class,
                () -> wrapper.generate(List.of(), chunk -> {})).getMessage());
        assertEquals(0, delivered.get());
    }
    @Test void partialOrUnconfirmedOutputIsNeverCertified() {
        for (String reason : List.of("LENGTH", "TOOL_EXECUTION", "OTHER")) {
            var observations = new ArrayList<ObservedGenerationBackend.Observation>();
            new ObservedGenerationBackend(scripted("model","La porte A est ouverte.",reason),
                    REFERENCE, ModelExplorer.Budget.small(),observations::add).generate(List.of(),x -> {});
            assertFalse(observations.getFirst().fullySupported());
        }
    }
    @Test void observerFailureCannotAlterResultOrHistory() {
        var backend = new ObservedGenerationBackend(scripted("model","La porte A est ouverte.","STOP"),
                REFERENCE,ModelExplorer.Budget.small(),o -> { throw new IllegalArgumentException("sink down"); });
        var conversation = new NativeConversation(backend);
        assertEquals("La porte A est ouverte.",conversation.ask("question",text -> {}).text());
        assertEquals(2,conversation.history().size());
    }
    @Test void noExternalFactsAreAdmittedFromEarlierGeneratedTurns() {
        var observations = new ArrayList<ObservedGenerationBackend.Observation>();
        var backend = new ObservedGenerationBackend(scripted("model","La porte A est fermée.","STOP"),
                REFERENCE,ModelExplorer.Budget.small(),observations::add);
        var chat = new NativeConversation(backend);
        chat.ask("one",part -> {});
        chat.ask("two",part -> {});
        assertEquals(2,observations.size());
        assertTrue(observations.stream().allMatch(o -> o.review().orElseThrow().status()
                == P31CandidateAnswerAudit.Status.CONSISTENT_UNSUPPORTED));
    }
}
