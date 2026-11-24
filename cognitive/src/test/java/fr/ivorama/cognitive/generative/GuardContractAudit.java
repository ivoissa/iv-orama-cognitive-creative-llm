package fr.ivorama.cognitive.generative;
import com.fasterxml.jackson.databind.*;import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.security.LlmSecurityPolicy;import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
/** Adversarial provider fixture. Explicitly NOT a real LLM or a Qwen quality score. */
public final class GuardContractAudit {
 static final ObjectMapper JSON=new ObjectMapper();
 static GenerationBackend.Result reply(String s){return new GenerationBackend.Result(s,"adversarial-fixture","STOP",0,-1,null,null);}
 public static void main(String[] args)throws Exception {
  var document=JSON.readTree(Path.of(args[0]).toFile());String canary=document.path("canary").asText();var records=new ArrayList<Map<String,Object>>();
  for(Mode mode:Mode.values()){
   var guard=new LlmSecurityGateway(new LlmSecurityPolicy(mode,4096,4096,List.of(canary)),e->{}).wrap((m,p)->{p.accept("public");return reply("public");});
   for(int i=0;i<100;i++)guard.generate(List.of(UserMessage.from("Question publique")),s->{});
  }
  for(var c:document.path("cases")){
   var modes=new ArrayList<>(List.of(Mode.values()));Collections.shuffle(modes,new Random(511+c.path("id").asText().hashCode()));
   for(Mode mode:modes){var events=new ArrayList<LlmSecurityGateway.Event>();var calls=new AtomicInteger();var visible=new StringBuilder();String expectedOutput=c.path("output").asText();
    GenerationBackend provider=(m,p)->{calls.incrementAndGet();for(int i=0;i<expectedOutput.length();i+=7)p.accept(expectedOutput.substring(i,Math.min(i+7,expectedOutput.length())));return reply(expectedOutput);};
    var guard=new LlmSecurityGateway(new LlmSecurityPolicy(mode,4096,4096,List.of(canary)),events::add).wrap(provider);var row=new LinkedHashMap<String,Object>();row.put("id",c.path("id").asText());row.put("family",c.path("family").asText());row.put("mode",mode);row.put("expectedBlocked",mode==Mode.ENFORCE&&c.path("blocked").asBoolean());var first=new AtomicLong(-1);long start=System.nanoTime();
    try{var result=guard.generate(List.of(UserMessage.from(c.path("input").asText())),piece->{first.compareAndSet(-1,System.nanoTime()-start);visible.append(piece);});row.put("outcome","ALLOWED");row.put("responseText",result.text());}
    catch(LlmSecurityGateway.Blocked blocked){row.put("outcome","BLOCKED");row.put("code",blocked.code());}
    catch(RuntimeException error){row.put("outcome","ERROR");row.put("error",error.getClass().getSimpleName());}
    row.put("wallNanos",System.nanoTime()-start);row.put("firstVisibleNanos",first.get());row.put("providerCalls",calls.get());row.put("visibleText",visible.toString());row.put("events",events);row.put("rawOutputUnchanged",expectedOutput.equals(row.get("responseText"))&&expectedOutput.equals(visible.toString()));row.put("contractCorrect",((Boolean)row.get("expectedBlocked"))?row.get("outcome").equals("BLOCKED")&&visible.isEmpty():row.get("outcome").equals("ALLOWED")&&((Boolean)row.get("rawOutputUnchanged")));records.add(row);
   }
  }
  JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("scope","Deterministic contract audit, no real LLM invocation","records",records));System.out.println("ROWS="+records.size());
 }
}
