package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.UserMessage;
import fr.ivorama.cognitive.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full local-llm Maven profile, REAL SDK message classes, simulated provider. */
class P34SuggestedGenerationBackendTest {
    private static GenerationBackend scripted(String text,String model,String finish) {
        return (messages,partial)->{
            partial.accept("chunk");
            return new GenerationBackend.Result(text,model,finish,123L,1L,2,3);
        };
    }
    @Test void providerInterchangeabilityAndByteForBytePreservation(){
        for(String model:List.of("Qwen","GPT","Claude","Gemini","custom")){
            var turns=new ArrayList<SuggestedGenerationBackend.AdvisoryTurn>();
            var stream=new ArrayList<String>();
            var wrapper=new SuggestedGenerationBackend(scripted("La porte A n'est pas ouverte.",model,"STOP"),
                    "La porte A est ouverte.",ModelExplorer.Budget.small(),turns::add);
            var out=wrapper.generate(List.of(UserMessage.from("query")),stream::add);
            assertEquals("La porte A n'est pas ouverte.",out.text());
            assertEquals(List.of("chunk"),stream);
            assertEquals(3,out.outputTokens());
            assertEquals(1,turns.size());
            assertEquals(P32SuggestedCorrections.Action.CHALLENGE,
                    turns.getFirst().advice().orElseThrow().suggestions().getFirst().action());
        }
    }
    @Test void truncatedOutputNeverGeneratesAdvice(){
        var turns=new ArrayList<SuggestedGenerationBackend.AdvisoryTurn>();
        new SuggestedGenerationBackend(scripted("La porte A est ouverte.","any","LENGTH"),
                "La porte A est ouverte.",ModelExplorer.Budget.small(),turns::add)
                .generate(List.of(),s->{});
        assertTrue(turns.getFirst().advice().isEmpty());
    }
    @Test void consumerFailureCannotBreakGeneration(){
        var wrapper=new SuggestedGenerationBackend(scripted("La porte A est ouverte.","any","STOP"),
                "La porte A est ouverte.",ModelExplorer.Budget.small(),
                x->{throw new IllegalStateException("subscriber unavailable");});
        assertEquals("La porte A est ouverte.",wrapper.generate(List.of(),s->{}).text());
    }
}
