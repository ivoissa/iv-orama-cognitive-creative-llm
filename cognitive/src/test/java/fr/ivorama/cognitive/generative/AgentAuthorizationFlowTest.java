package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.security.*;import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import fr.ivorama.cognitive.agent.*;import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class AgentAuthorizationFlowTest {
 @TempDir Path root;
 static GenerationBackend.Result reply(String text){return new GenerationBackend.Result(text,"fixture","STOP",1,1,1,1);}
 static void configured(Map<String,String> props,Throwing task)throws Exception {var old=new HashMap<String,String>();props.forEach((k,v)->{old.put(k,System.getProperty(k));System.setProperty(k,v);});try{task.run();}finally{old.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}}
 interface Throwing{void run()throws Exception;}
 @Test void actualQwenObserverPreservesCallBudgetAndDoesNotReceiveForeignSearchData()throws Exception{
  Files.createDirectories(root.resolve("public"));Files.createDirectories(root.resolve("foreign"));Files.writeString(root.resolve("public/A.java"),"class A {} // TARGET public\n");Files.writeString(root.resolve("foreign/B.java"),"class B {} // TARGET OUTSIDE_MARKER\n");Path file=root.resolve("policy.json");Files.writeString(file,"{\"readScope\":\"public\"}");
  var contexts=new HashMap<String,String>();var toolCounts=new HashMap<String,Integer>();
  for(String mode:List.of("OFF","AUDIT","ENFORCE"))configured(Map.of("ivorama.agent.authorizationMode",mode,"ivorama.agent.authorizationPolicyFile",file.toString()),()->{
   var calls=new AtomicInteger();var context=new StringBuilder();GenerationBackend backend=(messages,partial)->{messages.forEach(m->context.append(m.toString()));return reply(switch(calls.getAndIncrement()){
    case 0 -> "{\"operation\":\"SEARCH\",\"query\":\"TARGET\"}";
    case 1 -> "{\"operation\":\"READ\",\"path\":\"public/A.java\"}";
    default -> "{\"operation\":\"FINISH\"}";
   });};var r=new QwenObserverAgent(root,backend,backend,true).investigate("Inspect TARGET");assertEquals(3,calls.get());assertEquals(3,r.toolCalls());assertTrue(r.toolCalls()<=5);contexts.put(mode,context.toString());toolCounts.put(mode,r.toolCalls());
  });
  assertEquals(contexts.get("OFF"),contexts.get("AUDIT"));assertTrue(contexts.get("OFF").contains("OUTSIDE_MARKER"));assertFalse(contexts.get("ENFORCE").contains("OUTSIDE_MARKER"));assertFalse(contexts.get("ENFORCE").contains("foreign/B.java"));assertEquals(toolCounts.get("OFF"),toolCounts.get("ENFORCE"));
 }
 @Test void patchModeIsDeniedBeforeAnyModelCallUnlessExplicitlyGranted()throws Exception{
  configured(Map.of("ivorama.agent.authorizationMode","ENFORCE"),()->{GenerationBackend model=(m,p)->{fail("No model call before authorization");return reply("{}");};assertThrows(AgentAuthorizationGate.Blocked.class,()->new QwenPatchProposalAgent(root,model,model).propose("Patch ?"));});
 }
 @Test void validationIsASeparateCapabilityAndPartialScopesCannotGrantWholeProjectCopy(){
  var previewOnly=new AgentAuthorizationPolicy(Mode.ENFORCE,".",Set.of(ObserverSession.Operation.values()),true,false,false,false,true,false);var gate=new AgentAuthorizationGate(previewOnly,e->{});assertDoesNotThrow(()->gate.beforePatch(false));assertThrows(AgentAuthorizationGate.Blocked.class,()->gate.beforePatch(true));
  assertThrows(IllegalArgumentException.class,()->new AgentAuthorizationPolicy(Mode.ENFORCE,"public",Set.of(ObserverSession.Operation.values()),true,false,false,false,true,true));
 }
 @Test void authorizationAndLlmSecurityAreIndependent()throws Exception{
  configured(Map.of("ivorama.agent.authorizationMode","ENFORCE","ivorama.security.mode","OFF"),()->{assertEquals(Mode.OFF,LlmSecurityPolicy.fromSystemProperties().mode());assertEquals(Mode.ENFORCE,AgentAuthorizationPolicy.fromSystemProperties().mode());});
 }
}
