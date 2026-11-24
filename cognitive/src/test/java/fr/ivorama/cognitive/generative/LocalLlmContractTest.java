package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** SIMULATED Ollama HTTP contracts. These tests do not evaluate an LLM. */
class LocalLlmContractTest {
    private final ObjectMapper json=new ObjectMapper();
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();
        Fixture(HttpHandler handler)throws Exception{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);server.createContext("/",handler);server.start();}
        LocalLlmConfig config(){return new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"contract:local",4096,128,2,17,Duration.ofSeconds(5),0.2);}
        public void close(){server.stop(0);pool.shutdownNow();}
    }
    private static void send(HttpExchange x,int status,String body)throws java.io.IOException{
        x.getResponseHeaders().set("Content-Type","application/x-ndjson");byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}
    }
    private static String stream(String finish){return """
        {"model":"contract:local","message":{"role":"assistant","content":"Texte "},"done":false}
        {"model":"contract:local","message":{"role":"assistant","content":"simulé."},"done":false}
        {"model":"contract:local","message":{"role":"assistant","content":""},"done":true,"done_reason":"%s","prompt_eval_count":12,"eval_count":4}
        """.formatted(finish);}
    @Test void sdkStreamsAndSendsExplicitCpuBudgets()throws Exception{
        var received=new AtomicReference<JsonNode>();
        try(var f=new Fixture(x->{received.set(json.readTree(x.getRequestBody()));send(x,200,stream("stop"));})){
            var pieces=new StringBuilder();var session=new LocalConversation(new OllamaGenerationBackend(f.config()),17);
            var r=session.ask("Question de contrat",pieces::append);
            assertEquals("Texte simulé.",r.text());assertEquals(r.text(),pieces.toString());assertTrue(r.firstTextNanos()>=0);assertTrue(r.wallNanos()>=r.firstTextNanos());
            assertEquals(4,r.outputTokens());assertEquals(12,r.inputTokens());assertEquals(3,session.history().size());
            var body=received.get();assertEquals(false,body.path("think").asBoolean(true));assertEquals(false,body.path("truncate").asBoolean(true));
            assertTrue(body.path("stream").asBoolean());assertEquals(0,body.path("options").path("num_gpu").asInt(-1));
            assertEquals(2,body.path("options").path("num_thread").asInt());assertEquals(4096,body.path("options").path("num_ctx").asInt());
            assertEquals(128,body.path("options").path("num_predict").asInt());assertEquals(0,body.path("tools").size());
        }
    }
    @Test void sdkPlannerRequestsJsonButStillChecksSchema()throws Exception{
        var received=new AtomicReference<JsonNode>();
        String plan=RequestPlannerTest.planUnchecked("EXPLANATION","FACTUAL_GAP","optique");
        try(var f=new Fixture(x->{
            received.set(json.readTree(x.getRequestBody()));
            send(x,200,json.writeValueAsString(Map.of("model","contract:local","message",Map.of("role","assistant","content",plan),"done",true,"done_reason","stop"))+"\n");
        })){
            var interpreted=new RequestPlanner(new OllamaGenerationBackend(f.config(),true)).interpret("Explique optique",List.of());
            assertTrue(interpreted.usable());assertEquals("json",received.get().path("format").asText());
            assertEquals(0,received.get().path("tools").size());assertEquals("optique",interpreted.plan().terms());
        }
    }
    @Test void failureDoesNotInventOrCommitAnswer()throws Exception{
        try(var f=new Fixture(x->send(x,404,"{\"error\":\"model missing\"}"))){
            var session=new LocalConversation(new OllamaGenerationBackend(f.config()),17);
            assertThrows(IllegalStateException.class,()->session.ask("Demande",t->{}));assertEquals(1,session.history().size());
        }
    }
    @Test void truncatedTurnIsNotRemembered()throws Exception{
        try(var f=new Fixture(x->send(x,200,stream("length")))){
            var session=new LocalConversation(new OllamaGenerationBackend(f.config()),17);var r=session.ask("Demande",t->{});
            assertTrue(r.truncated());assertEquals(1,session.history().size());
        }
    }
    @Test void emptyOutputIsAnError()throws Exception{
        try(var f=new Fixture(x->send(x,200,"{\"model\":\"contract:local\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n"))){
            var session=new LocalConversation(new OllamaGenerationBackend(f.config()),17);
            assertThrows(RuntimeException.class,()->session.ask("Demande",t->{}));assertEquals(1,session.history().size());
        }
    }
    @Test void callerFailureDoesNotCommitTurn()throws Exception{
        try(var f=new Fixture(x->send(x,200,stream("stop")))){
            var session=new LocalConversation(new OllamaGenerationBackend(f.config()),17);
            assertThrows(RuntimeException.class,()->session.ask("Demande",t->{throw new IllegalStateException("consumer failed");}));
            assertEquals(1,session.history().size());
        }
    }
    @Test void sessionsAreIsolatedAndMemoryBounded(){
        var observed=new AtomicReference<List<ChatMessage>>();
        GenerationBackend backend=(messages,partial)->{observed.set(messages);return new GenerationBackend.Result("Sortie de test","fixture","STOP",100,10,2,3);};
        var a=new LocalConversation(backend,5);var b=new LocalConversation(backend,5);
        a.ask("Contexte A",t->{});a.ask("Suite",t->{});assertEquals(4,observed.get().size());assertEquals(1,b.history().size());
        for(int i=0;i<8;i++)a.ask("Suite "+i,t->{});assertEquals(5,a.history().size());assertInstanceOf(SystemMessage.class,a.history().getFirst());
        a.reset();assertEquals(1,a.history().size());assertEquals(1,b.history().size());
    }
    @Test void rejectsRemoteEndpointsCloudTagsAndInvalidBudgets(){
        for(String url:List.of("https://127.0.0.1:1234","http://example.com:1234","http://127.0.0.1:1234/api","http://user:pwd@localhost:1234","http://localhost:1234?x=y"))
            assertThrows(IllegalArgumentException.class,()->new LocalLlmConfig(URI.create(url),"test:local",4096,128,8,17,Duration.ofSeconds(5),0.2));
        for(String model:List.of("test","test:cloud","bad name:local"))assertThrows(IllegalArgumentException.class,()->new LocalLlmConfig(URI.create("http://localhost:1234"),model,4096,128,8,17,Duration.ofSeconds(5),0.2));
        assertThrows(IllegalArgumentException.class,()->new LocalLlmConfig(URI.create("http://localhost:1234"),"test:local",512,512,8,17,Duration.ofSeconds(5),0.2));
    }
    @Test void noAutomaticModelDownloadAndDiagnosticFindsDigest()throws Exception{
        var paths=new CopyOnWriteArrayList<String>();
        try(var f=new Fixture(x->{paths.add(x.getRequestURI().getPath());send(x,200,"{\"models\":[{\"name\":\"other:local\",\"digest\":\"abc\"}]}");})){
            assertThrows(IllegalStateException.class,()->new OllamaProbe(f.config()).requireInstalled());assertEquals(List.of("/api/tags"),paths);
        }
        try(var f=new Fixture(x->send(x,200,"{\"models\":[{\"name\":\"contract:local\",\"digest\":\"abc\"}]}"))){
            assertEquals("abc",new OllamaProbe(f.config()).requireInstalled().path("digest").asText());
        }
    }
    @Test void oversizedInputNeverCallsBackend(){
        var called=new AtomicInteger();GenerationBackend backend=(messages,partial)->{called.incrementAndGet();throw new AssertionError();};
        var session=new LocalConversation(backend,5);
        assertThrows(IllegalArgumentException.class,()->session.ask("x".repeat(12001),t->{}));assertThrows(IllegalArgumentException.class,()->session.ask(" ",t->{}));assertEquals(0,called.get());
    }

    @Test void contextGuardCompactsHistoryButPreservesCurrentQuestion()throws Exception{
        var received=new AtomicReference<JsonNode>();
        try(var f=new Fixture(x->{received.set(json.readTree(x.getRequestBody()));send(x,200,stream("stop"));})){
            var prompt=List.<ChatMessage>of(SystemMessage.from("Cadre"),
                    UserMessage.from("OLD_USER_"+"x".repeat(4900)),AiMessage.from("OLD_AI_"+"y".repeat(4900)),
                    UserMessage.from("RECENT_USER"),AiMessage.from("RECENT_ASSISTANT"),
                    UserMessage.from("CURRENT_USER"));
            var result=new OllamaGenerationBackend(f.config()).generate(prompt,t->{});
            assertEquals("Texte simulé.",result.text());
            var sent=received.get().path("messages").toString();
            assertFalse(sent.contains("OLD_USER_"));assertFalse(sent.contains("OLD_AI_"));
            assertTrue(sent.contains("RECENT_USER"));assertTrue(sent.contains("CURRENT_USER"));
            assertFalse(received.get().path("truncate").asBoolean(true));
        }
    }
    @Test void oversizeProtectedEvidenceFailsBeforeSendingAnyHttpRequest()throws Exception{
        var requests=new AtomicInteger();
        try(var f=new Fixture(x->{requests.incrementAndGet();send(x,200,stream("stop"));})){
            var prompt=List.<ChatMessage>of(SystemMessage.from("Cadre"),UserMessage.from("EVIDENCE_"+"x".repeat(12000)),UserMessage.from("Question"));
            assertThrows(IllegalArgumentException.class,()->new OllamaGenerationBackend(f.config()).generate(prompt,t->{}));
            assertEquals(0,requests.get());
        }
    }
}
