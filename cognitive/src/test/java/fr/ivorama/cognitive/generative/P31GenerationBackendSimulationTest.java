package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Profile-local-llm test: real GenerationBackend interface; no network or Ollama. */
final class P31GenerationBackendSimulationTest {
    @Test void sameAuditForAnyGenerationBackend() {
        String facts="La porte A est ouverte.";
        String draft="La porte A n'est pas ouverte.";
        for (String label : List.of("simulated-qwen", "simulated-gpt", "simulated-claude", "simulated-other")) {
            GenerationBackend backend=(messages,partials)->new GenerationBackend.Result(
                draft,label,"STOP",1L,-1L,null,null);
            var answer=new NativeConversation(backend).ask("Question de test",chunk->{});
            var verdict=P31CandidateAnswerAudit.inspect(answer.model(),facts,answer.text(),ModelExplorer.Budget.small());
            assertEquals(P31CandidateAnswerAudit.Status.CONTRADICTED,verdict.status());
            assertEquals(draft,verdict.draft());
        }
    }
    @Test void unrecognizedModelOutputNeverGetsInventedVerdict() {
        GenerationBackend backend=(messages,partials)->new GenerationBackend.Result(
            "Sans doute ; c'est impossible à déterminer.","model-any","STOP",1,-1,null,null);
        var answer=new NativeConversation(backend).ask("Question de test",ignored->{});
        var review=P31CandidateAnswerAudit.inspect(answer.model(),"La porte A est ouverte.",
             answer.text(),ModelExplorer.Budget.small());
        assertEquals(P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE, review.status());
    }
}
