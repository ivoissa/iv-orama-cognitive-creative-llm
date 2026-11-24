package fr.ivorama.cognitive.generative;
import com.sun.net.httpserver.HttpServer;import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;import java.net.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.time.Duration;import java.util.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import static org.junit.jupiter.api.Assertions.*;
/** Real SDK and HTTP fixture, not a Qwen quality experiment. */
class LlmSecurityWireTest {
 @TempDir Path root;
 static final String CANARY="wire-canary-credential-92417";
 @Test void everyOllamaFactoryChecksBeforeDispatchAndBeforeStreaming()throws Exception {
  var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var json=new ObjectMapper();
  server.createContext("/api/chat",exchange->{
   requests.incrementAndGet();exchange.getRequestBody().readAllBytes();StringBuilder reply=new StringBuilder();
   for(String text:List.of("wire-canary-","credential-92417"))reply.append(json.writeValueAsString(Map.of("model","wire:tag","message",Map.of("role","assistant","content",text),"done",false))).append('\n');
   reply.append("{\"model\":\"wire:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":8,\"eval_count\":8}\n");
   byte[] bytes=reply.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/x-ndjson");exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
  });server.start();var previous=new HashMap<String,String>();Path values=root.resolve("protected.json");Files.writeString(values,json.writeValueAsString(List.of(CANARY)));
  Map<String,String> props=Map.of("ivorama.security.mode","ENFORCE","ivorama.security.protectedValuesFile",values.toString());props.forEach((k,v)->{previous.put(k,System.getProperty(k));System.setProperty(k,v);});
  try {
   var config=new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"wire:tag",4096,128,2,4,Duration.ofSeconds(5),0);
   var backends=List.of(new OllamaGenerationBackend(config),new OllamaGenerationBackend(config,true),OllamaGenerationBackend.nativeText(config),OllamaGenerationBackend.reasonedComment(config));
   for(var backend:backends){int before=requests.get();assertThrows(LlmSecurityGateway.Blocked.class,()->backend.generate(List.of(UserMessage.from(CANARY)),s->fail()));assertEquals(before,requests.get());var visible=new StringBuilder();assertThrows(LlmSecurityGateway.Blocked.class,()->backend.generate(List.of(UserMessage.from("Question publique")),visible::append));assertEquals(before+1,requests.get());assertEquals("",visible.toString());}
  }finally{previous.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});server.stop(0);}
 }
}
