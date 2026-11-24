package fr.ivorama.cognitive.generative;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.generation.TurnBudget;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK + local HTTP fixture, no Qwen: verify request limits on the wire, not just budget arithmetic. */
class Corrective073WireTest {
    @Test void aggregateOutputLimitAndGpuReachTheActualSdkRequest()throws Exception{
        var json=new ObjectMapper();var received=new CopyOnWriteArrayList<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",exchange->{
            received.add(json.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response=("{\"model\":\"budget-fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"Réponse.\"},\"done\":false}\n"+
                    "{\"model\":\"budget-fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},"+
                    "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":20}\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.sendResponseHeaders(200,response.length);
            try(var body=exchange.getResponseBody()){body.write(response);}
        });
        server.start();String previous=System.getProperty("ivorama.llm.gpu");
        try{
            System.setProperty("ivorama.llm.gpu","3");
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"budget-fixture:tag",1024,128,4,17,Duration.ofSeconds(5),0);
            var adapter=new OllamaGenerationBackend(config);
            try(var budget=new TurnBudget(32,Duration.ofSeconds(5))){
                var result=adapter.generate(List.of(UserMessage.from("Test de transport simulé.")),t->{});
                assertEquals(20,result.outputTokens());assertEquals(12,budget.remainingOutputTokens());
                assertThrows(IllegalStateException.class,()->adapter.generate(List.of(UserMessage.from("Aucun second appel.")),t->{}));
            }
            assertEquals(1,received.size());assertEquals(32,received.getFirst().path("options").path("num_predict").asInt());
            assertEquals(3,received.getFirst().path("options").path("num_gpu").asInt());
        }finally{
            if(previous==null)System.clearProperty("ivorama.llm.gpu");else System.setProperty("ivorama.llm.gpu",previous);
            server.stop(0);
        }
    }
}
