package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObserverPlanningContractTest {
    @Test void planningFactoryKeepsTheExistingJsonRequest() throws Exception {
        var json=new ObjectMapper();var requests=new ArrayList<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat",ex->{requests.add(json.readTree(ex.getRequestBody()));String body=json.writeValueAsString(Map.of("model","fixture:tag","message",Map.of("role","assistant","content","{\"operation\":\"FINISH\"}"),"done",true,"done_reason","stop"))+"\n";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/x-ndjson");ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});server.start();
        String previous=System.getProperty("ivorama.agent.planFormat");System.clearProperty("ivorama.agent.planFormat");
        try {
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture:tag",4096,64,1,17,Duration.ofSeconds(5),0);
            var messages=List.<dev.langchain4j.data.message.ChatMessage>of(UserMessage.from("Planifie une action."));
            new OllamaGenerationBackend(config,true).generate(messages,p->{});OllamaGenerationBackend.observerPlan(config).generate(messages,p->{});
            assertEquals(2,requests.size());assertEquals(requests.get(0),requests.get(1));assertEquals("json",requests.get(1).path("format").asText());
        } finally {server.stop(0);if(previous==null)System.clearProperty("ivorama.agent.planFormat");else System.setProperty("ivorama.agent.planFormat",previous);}
    }
    @Test void removedExperimentalModeFailsExplicitlyBeforeAnyModelCall() {
        String previous=System.getProperty("ivorama.agent.planFormat");System.setProperty("ivorama.agent.planFormat","SCHEMA");
        try {
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:11434"),"fixture:tag",4096,64,1,17,Duration.ofSeconds(5),0);
            assertTrue(assertThrows(IllegalArgumentException.class,()->OllamaGenerationBackend.observerPlan(config)).getMessage().contains("retiré"));
        } finally {if(previous==null)System.clearProperty("ivorama.agent.planFormat");else System.setProperty("ivorama.agent.planFormat",previous);}
    }
}
