package fr.ivorama.cognitive.generative;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK + HTTP fixture, not Qwen quality scores. Compare full requests and exact returned text. */
class Native075WireTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RAW = "  Texte Qwen natif.\nÉté, JSON {\"z\":2,\"a\":1}.  \n";
    private static HttpServer server(List<JsonNode> requests) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/chat", exchange -> {
            requests.add(JSON.readTree(exchange.getRequestBody()));
            String response = JSON.writeValueAsString(Map.of("model","native-fixture:tag","message",Map.of("role","assistant","content",RAW),"done",false))+"\n"+
                    "{\"model\":\"native-fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":12,\"eval_count\":21}\n";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();return server;
    }
    @Test void defaultNativeFactorySendsTheSameRequestAsTheUnmodifiedSdkAdapter() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=server(requests);
        String oldSeed=System.getProperty("ivorama.llm.seed"),oldThink=System.getProperty("ivorama.llm.think");
        try {
            System.setProperty("ivorama.llm.seed","42");System.setProperty("ivorama.llm.think","false");
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"native-fixture:tag",4096,128,2,17,Duration.ofSeconds(5),0);
            String question="  :analyser {\"z\":2,\"a\":1} avec zéro phrase.  ";
            var direct=new OllamaGenerationBackend(config).generate(List.of(UserMessage.from(question)),ignored->{});
            var nativeResult=NativeConversation.ollama(config).ask(question,ignored->{});
            assertEquals(RAW,direct.text());assertEquals(RAW,nativeResult.text());
            assertEquals(2,requests.size());assertEquals(requests.getFirst(),requests.getLast());
            JsonNode request=requests.getLast();
            assertEquals(1,request.path("messages").size());
            assertEquals("user",request.path("messages").get(0).path("role").asText());
            assertEquals(question,request.path("messages").get(0).path("content").asText());
            assertFalse(request.hasNonNull("format"));
            assertFalse(request.path("think").asBoolean());
        } finally {restore("ivorama.llm.seed",oldSeed);restore("ivorama.llm.think",oldThink);server.stop(0);}
    }
    @Test void nativePathNeverDropsOrRewritesMessagesToSatisfyTheIvPromptProxy() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=server(requests);
        try {
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"native-fixture:tag",512,128,2,4,Duration.ofSeconds(5),0);
            String question="Contexte original : "+"long texte fourni ".repeat(150);
            assertThrows(IllegalArgumentException.class,()->new OllamaGenerationBackend(config).generate(List.of(UserMessage.from(question)),ignored->{}));
            assertTrue(requests.isEmpty());
            assertEquals(RAW,NativeConversation.ollama(config).ask(question,ignored->{}).text());
            assertEquals(1,requests.size());
            assertEquals(question,requests.getFirst().path("messages").get(0).path("content").asText());
        } finally {server.stop(0);}
    }
    @Test void demoWithoutArgumentsUsesNativeEvenWhenAllLegacyIvFlagsAreEnabled() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=server(requests);
        for (String endpoint : List.of("/api/version","/api/tags")) server.createContext(endpoint, exchange -> {
            String body=endpoint.endsWith("version")?"{\"version\":\"fixture\"}":"{\"models\":[{\"name\":\"native-fixture:tag\"}]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        var properties=Map.of("ivorama.llm.url","http://127.0.0.1:"+server.getAddress().getPort(),
                "ivorama.llm.model","native-fixture:tag","ivorama.llm.memory","4","ivorama.llm.wikipedia","true",
                "ivorama.llm.interpret","true","ivorama.llm.autoReason","true","ivorama.llm.candidate073","true");
        var previous=new HashMap<String,String>();properties.forEach((key,value)->previous.put(key,System.getProperty(key)));
        var previousInput=System.in;var previousOutput=System.out;
        try {
            properties.forEach(System::setProperty);
            System.setIn(new java.io.ByteArrayInputStream(":raisonner P -> Q\nSuite 1\nSuite 2\n:quitter\n".getBytes(StandardCharsets.UTF_8)));
            var output=new java.io.ByteArrayOutputStream();System.setOut(new java.io.PrintStream(output));
            fr.ivorama.cognitive.Demo.main(new String[0]);
            assertEquals(3,requests.size());
            assertEquals(5,requests.getLast().path("messages").size());
            assertEquals(1,requests.getFirst().path("messages").size());
            assertEquals(":raisonner P -> Q",requests.getFirst().path("messages").get(0).path("content").asText());
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Qwen > "+RAW));
        } finally {
            previous.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});
            System.setIn(previousInput);System.setOut(previousOutput);server.stop(0);
        }
    }
    @Test void nativeDoesNotDisableThinkingOrImposeASeedByDefault() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=server(requests);
        String oldSeed=System.getProperty("ivorama.llm.seed"),oldThink=System.getProperty("ivorama.llm.think");
        try {
            System.clearProperty("ivorama.llm.seed");System.clearProperty("ivorama.llm.think");
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"native-fixture:tag",4096,128,2,4,Duration.ofSeconds(5),0);
            NativeConversation.ollama(config).ask("Question",ignored->{});
            assertFalse(requests.getFirst().hasNonNull("think"));
            assertFalse(requests.getFirst().path("options").hasNonNull("seed"));
        } finally {restore("ivorama.llm.seed",oldSeed);restore("ivorama.llm.think",oldThink);server.stop(0);}
    }
    @Test void explicitNativeThinkingAndSeedAreTransmittedWithoutAnIvOverride() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();var server=server(requests);
        String oldSeed=System.getProperty("ivorama.llm.seed"),oldThink=System.getProperty("ivorama.llm.think");
        try {
            System.setProperty("ivorama.llm.seed","7");System.setProperty("ivorama.llm.think","true");
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"native-fixture:tag",4096,128,2,4,Duration.ofSeconds(5),0);
            NativeConversation.ollama(config).ask("Question",ignored->{});
            assertTrue(requests.getFirst().path("think").asBoolean());
            assertEquals(7,requests.getFirst().path("options").path("seed").asInt());
        } finally {restore("ivorama.llm.seed",oldSeed);restore("ivorama.llm.think",oldThink);server.stop(0);}
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}
