package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.function.Consumer;

/** Provider seam; a generated answer carries no formal proof status. */
public interface GenerationBackend {
    record Result(String text,String model,String finishReason,long wallNanos,long firstTextNanos,
                  Integer inputTokens,Integer outputTokens) {
        public Result {
            if(text==null||text.isBlank())throw new IllegalArgumentException("Réponse vide du modèle");
        }
        /** End-to-end rate includes prompt processing and loading; NOT decode tokens/s. */
        public Double endToEndTokensPerSecond(){return outputTokens==null||wallNanos<=0?null:outputTokens*1_000_000_000.0/wallNanos;}
        public boolean truncated(){return "LENGTH".equalsIgnoreCase(finishReason);}
    }
    Result generate(List<ChatMessage> messages,Consumer<String> partial);
    static GenerationBackend measured(GenerationBackend delegate,String stage){
        java.util.Objects.requireNonNull(delegate);
        return (messages,partial)->{
            long start=System.nanoTime();
            try{
                var result=delegate.generate(messages,partial);
                fr.ivorama.cognitive.generation.TurnTelemetry.call(stage,System.nanoTime()-start,
                        result.inputTokens(),result.outputTokens(),result.finishReason());
                return result;
            }catch(RuntimeException | Error failure){
                fr.ivorama.cognitive.generation.TurnTelemetry.call(stage,System.nanoTime()-start,null,null,"ERROR");
                throw failure;
            }
        };
    }
}
