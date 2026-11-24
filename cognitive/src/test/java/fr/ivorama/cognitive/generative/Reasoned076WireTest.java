package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import fr.ivorama.cognitive.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK / deterministic HTTP fixture. Transport contracts only, not native Qwen quality. */
class Reasoned076WireTest {
    @TempDir Path root;
    private static ReasonedObserverSession.Plan plan() {
        return new ReasonedObserverSession.Plan("wire",true,Map.of("a",Map.of("signal",true),"b",Map.of("signal",false)),
                List.of(new ReasonedObserverSession.Probe("signal","measurement.txt",1,40,"probe=oui","probe=non")));
    }
    @Test void threeCasesSendExactlyOneCommentaryCallAndKeepTheOfflineReceipt() throws Exception {
        var requests = new ArrayList<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var json=new ObjectMapper();
        server.createContext("/api/chat",exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            String text="{\"text\":\"La causalité réelle reste à vérifier.\",\"evidenceIds\":[]}";
            String data=json.writeValueAsString(Map.of("model","fixture:tag","message",Map.of("role","assistant","content",text),"done",false))+"\n"
                    + "{\"model\":\"fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":100,\"eval_count\":40}\n";
            byte[] bytes=data.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try {
            var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fixture:tag",4096,256,2,17,Duration.ofSeconds(5),0);
            for(String text:List.of("probe=oui\n","aucune mesure\n","probe=oui\nprobe=non\n")) {
                Files.writeString(root.resolve("measurement.txt"),text);
                int before=requests.size();
                var offline=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null).investigate("Cause ?");
                assertEquals(before,requests.size());
                var online=QwenObserverAgent.investigateReasoned(root,plan(),"Cause ?",OllamaGenerationBackend.reasonedComment(config),InvestigationOptions.defaults(),QwenObserverAgent.ReasonedCommentPolicy.ALWAYS);
                assertEquals(before+1,requests.size());assertEquals(offline.finding(),online.finding());assertEquals(offline.steps(),online.steps());
                assertEquals("",online.commentaryError());assertTrue(online.report().unsupportedCitations().isEmpty());
                assertEquals(2,requests.getLast().path("messages").size());
                assertTrue(requests.getLast().path("format").isObject());
                assertFalse(requests.getLast().path("format").path("additionalProperties").asBoolean(true));
                assertEquals(320,requests.getLast().path("format").path("properties").path("text").path("maxLength").asInt());
                assertTrue(requests.getLast().path("messages").get(1).path("content").asText().contains(offline.finding().state().name()));
            }
        } finally {server.stop(0);}
    }
    @Test void fabricatedModelCitationsAreRejectedWithoutLosingCoreConclusion() throws Exception {
        Files.writeString(root.resolve("measurement.txt"),"probe=oui\n");
        GenerationBackend invalid=(messages,partial)->new GenerationBackend.Result(
                "{\"text\":\"Inventé.\",\"evidenceIds\":[\"F99\"]}","fixture","STOP",1,1,20,20);
        var result=QwenObserverAgent.investigateReasoned(root,plan(),"Cause ?",invalid);
        assertEquals("a",result.finding().cause());assertTrue(result.commentaryError().contains("AGENT_COMMENT_REJECTED"));
        assertFalse(result.report().answer().contains("Inventé"));
    }
    @Test void offlineDemoNeedsNoOllamaAndNativeRemainsDefault() throws Exception {
        Files.writeString(root.resolve("measurement.txt"),"probe=oui\n");
        Path model=root.resolve("plan.json");new ObjectMapper().writeValue(model.toFile(),plan());
        var old=System.out;var bytes=new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(bytes));
            fr.ivorama.cognitive.Demo.main(new String[]{"--agent-reason-offline",root.toString(),model.toString(),"Cause ?"});
        } finally {System.setOut(old);}
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("PROVEN_CONDITIONALLY"));
        assertEquals(LocalAssistantCli.Mode.NATIVE,LocalAssistantCli.modeOf(new String[0]));
        assertEquals(LocalAssistantCli.Mode.REASONED,LocalAssistantCli.modeOf(new String[]{"--observe-reasoned","root","plan","question"}));
        assertThrows(IllegalArgumentException.class,()->LocalAssistantCli.modeOf(new String[]{"--observe-reasoned","root","plan"}));
    }
}
