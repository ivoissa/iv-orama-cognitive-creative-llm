package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class Reasoned078CommentContextTest {
 @TempDir Path root;
 @Test void ignoredNearMatchesNeverBecomeRawGenerationInputs()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes_extra\n// s=yes\n");
  var p=new ReasonedObserverSession.Plan("comment",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(new ReasonedObserverSession.Probe("s","s.txt",1,40,"s=yes","s=no")));
  var input=new AtomicReference<String>();GenerationBackend backend=(messages,partial)->{input.set(messages.toString());return new GenerationBackend.Result("{\"text\":\"Aucune valeur reconnue ne permet de conclure.\",\"evidenceIds\":[\"F1\"]}","fixture","STOP",1,1,10,10);};
  var result=QwenObserverAgent.investigateReasoned(root,p,"Conclusion ?",backend,InvestigationOptions.defaults(),QwenObserverAgent.ReasonedCommentPolicy.ALWAYS);
  assertTrue(input.get().contains("UNKNOWN"));assertFalse(input.get().contains("yes_extra"));assertFalse(input.get().contains("// s=yes"));
  assertEquals("",result.commentaryError());assertTrue(result.facts().isEmpty());assertEquals(LocalAssistantCli.Mode.NATIVE,LocalAssistantCli.modeOf(new String[0]));
 }
 @Test void autoPolicyDoesNotGenerateFromUnrecognizedText()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes_extra\n");
  var p=new ReasonedObserverSession.Plan("auto",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(new ReasonedObserverSession.Probe("s","s.txt",1,40,"s=yes","s=no")));
  GenerationBackend forbidden=(messages,partial)->{throw new AssertionError("Unexpected generation");};
  var r=QwenObserverAgent.investigateReasoned(root,p,"Conclusion ?",forbidden,InvestigationOptions.defaults());
  assertEquals("",r.commentaryError());assertFalse(r.report().answer().contains("COMMENTAIRE QWEN"));assertTrue(r.facts().isEmpty());
 }
 @Test void nonePolicyPreservesAnEstablishedConclusionWithoutCallingTheModel()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes\n");
  var p=new ReasonedObserverSession.Plan("none",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(new ReasonedObserverSession.Probe("s","s.txt",1,40,"s=yes","s=no")));
  GenerationBackend forbidden=(messages,partial)->{throw new AssertionError("Unexpected generation");};
  var r=QwenObserverAgent.investigateReasoned(root,p,"Conclusion ?",forbidden,InvestigationOptions.defaults(),QwenObserverAgent.ReasonedCommentPolicy.NONE);
  assertEquals("a",r.finding().cause());assertEquals("",r.commentaryError());
 }
}
