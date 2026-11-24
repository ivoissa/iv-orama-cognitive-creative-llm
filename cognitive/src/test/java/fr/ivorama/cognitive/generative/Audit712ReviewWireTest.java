package fr.ivorama.cognitive.generative;
import com.fasterxml.jackson.databind.ObjectMapper;import com.sun.net.httpserver.HttpServer;
import fr.ivorama.cognitive.agent.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.net.*;import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class Audit712ReviewWireTest {
 @TempDir Path root;
 @Test void reviewsFirstGeneratedSentenceAndPreservesTheOriginalReport()throws Exception{
  var json=new ObjectMapper();Files.writeString(root.resolve("a.txt"),"a=yes\n");
  var plan=new ReasonedObserverSession.Plan("wireguard",true,Map.of("alpha",Map.of("a",true),"beta",Map.of("a",false)),List.of(new ReasonedObserverSession.Probe("a","a.txt",1,40,"a=yes","a=no")));json.writeValue(root.resolve("plan.json").toFile(),plan);
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);int[] calls={0};
  server.createContext("/api/version",ex->{byte[] bytes="{\"version\":\"fixture\"}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});
  server.createContext("/api/tags",ex->{byte[] bytes="{\"models\":[{\"name\":\"fixture:tag\"}]}".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});
  server.createContext("/api/chat",ex->{calls[0]++;ex.getRequestBody().readAllBytes();String text="{\"text\":\"La cause est beta.\",\"evidenceIds\":[\"F1\"]}";String body=json.writeValueAsString(Map.of("model","fixture:tag","message",Map.of("role","assistant","content",text),"done",false))+"\n"+"{\"model\":\"fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\"}\n";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type","application/x-ndjson");ex.sendResponseHeaders(200,bytes.length);try(var o=ex.getResponseBody()){o.write(bytes);}});
  Properties saved=new Properties();saved.putAll(System.getProperties());var previous=System.out;var captured=new ByteArrayOutputStream();server.start();
  try{
   System.setProperty("ivorama.llm.url","http://127.0.0.1:"+server.getAddress().getPort());System.setProperty("ivorama.llm.model","fixture:tag");System.setProperty("ivorama.llm.timeout","10");System.setProperty("ivorama.agent.reasonedCommentary","ALWAYS");System.setProperty("ivorama.agent.decisionAuditMode","OFF");System.setProperty("ivorama.llm.reviewReasonedText","true");
   System.setOut(new PrintStream(captured,true,StandardCharsets.UTF_8));LocalAssistantCli.main(new String[]{"--observe-reasoned",root.toString(),root.resolve("plan.json").toString(),"Quel diagnostic ?"});
  }finally{System.setOut(previous);System.setProperties(saved);server.stop(0);}
  String output=captured.toString(StandardCharsets.UTF_8);assertEquals(1,calls[0]);assertTrue(output.contains("Commentaire généré, sens non certifié : La cause est beta."));assertTrue(output.contains("corrections proposées=1"));assertTrue(output.contains("la seule hypothèse compatible est «alpha»"));
 }
}
