package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.generation.TurnBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationOptionsWireTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    class Wire implements AutoCloseable {
        final HttpServer server;final List<JsonNode> requests=new ArrayList<>();
        Wire() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/version",ex->{byte[] bytes="{\"version\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});
            server.createContext("/api/tags",ex->{byte[] bytes="{\"models\":[{\"name\":\"fixture:tag\",\"digest\":\"fixture-digest\"}]}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});
            server.createContext("/api/chat",ex->{
                requests.add(json.readTree(ex.getRequestBody()));boolean truncated=requests.size()==2;String content=truncated?"Début conservé":"Bonjour.";
                String body=json.writeValueAsString(Map.of("model","fixture:tag","message",Map.of("role","assistant","content",content),"done",false))+"\n"+json.writeValueAsString(Map.of("model","fixture:tag","message",Map.of("role","assistant","content",""),"done",true,"done_reason",truncated?"length":"stop","prompt_eval_count",4,"eval_count",3))+"\n";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/x-ndjson");ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}
            });server.start();
        }
        LocalLlmConfig config(){return new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture:tag",4096,64,1,17,Duration.ofSeconds(10),0);}
        public void close(){server.stop(0);}
    }
    @Test void adapterKeepsItsOptionsWhenPropertiesChangeAndBudgetRebuildsIt() throws Exception {
        var saved=new Properties();saved.putAll(System.getProperties());
        try(var w=new Wire()) {
            System.setProperty("ivorama.security.mode","OFF");
            var adapter=OllamaGenerationBackend.nativeText(w.config(),new GenerationOptions(77,true));
            System.setProperty("ivorama.llm.seed","99");System.setProperty("ivorama.llm.think","false");
            try(var budget=new TurnBudget(32,Duration.ofSeconds(5))){adapter.generate(List.of(UserMessage.from("Texte exact")),ignored->{});}
            var request=w.requests.getFirst();assertEquals(77,request.path("options").path("seed").asInt());assertTrue(request.path("think").asBoolean());assertEquals(32,request.path("options").path("num_predict").asInt());assertEquals("Texte exact",request.path("messages").get(0).path("content").asText());assertEquals(1,w.requests.size());
        } finally {System.setProperties(saved);}
    }
}
