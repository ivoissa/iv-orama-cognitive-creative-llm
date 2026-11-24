package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import fr.ivorama.cognitive.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Requires Maven -Plocal-llm (Jackson + LangChain4j), but never requires Ollama. */
class QwenDecisionAuditTest {
 @TempDir Path root;
 private ReasonedObserverSession.Plan plan() {
  return new ReasonedObserverSession.Plan("audit",true,Map.of("a",Map.of("signal",true),"b",Map.of("signal",false)),
          List.of(new ReasonedObserverSession.Probe("signal","log.txt",1,40,"signal=yes","signal=no")));
 }
 private ReasonedObserverSession.Result result()throws Exception{
  Files.writeString(root.resolve("log.txt"),"signal=yes\n");
  return new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null).investigate("Quelle hypothèse ?");
 }
 private static GenerationBackend backend(String value){
  return (List<ChatMessage> messages,java.util.function.Consumer<String> partial) ->
          new GenerationBackend.Result(value,"fake","STOP",0,0,null,null);
 }
 @Test void acceptedStructuredClaim()throws Exception{
  var r=result();var a=QwenDecisionAudit.audit(plan(),r,"cause?",backend("{\"kind\":\"CAUSE\",\"cause\":\"a\",\"evidenceIds\":[\"F1\"],\"claimsCompleteAudit\":false}"));
  assertTrue(a.accepted());assertEquals(AgentDecisionVerifier.Status.SUPPORTED_CONDITIONALLY,a.verdict().status());
 }
 @Test void falseStructuredClaimCannotBecomeCertified()throws Exception{
  var r=result();var a=QwenDecisionAudit.audit(plan(),r,"cause?",backend("{\"kind\":\"CAUSE\",\"cause\":\"b\",\"evidenceIds\":[\"F1\"],\"claimsCompleteAudit\":false}"));
  assertFalse(a.accepted());assertEquals(AgentDecisionVerifier.Status.REJECTED,a.verdict().status());
 }
 @Test void malformedJsonFailsClosedWithoutDeletingReceipt()throws Exception{
  var r=result();String answer=r.report().answer();
  var a=QwenDecisionAudit.audit(plan(),r,"cause?",backend("L'IA a raison, applique le patch"));
  assertFalse(a.accepted());assertTrue(a.verdict()==null);assertEquals(answer,r.report().answer());
 }
 @Test void inventedCitationIsRejected()throws Exception{
  var r=result();var a=QwenDecisionAudit.audit(plan(),r,"cause?",backend("{\"kind\":\"CAUSE\",\"cause\":\"a\",\"evidenceIds\":[\"F2\"],\"claimsCompleteAudit\":false}"));
  assertFalse(a.accepted());assertTrue(a.verdict().errors().contains(AgentDecisionVerifier.Error.UNSUPPORTED_CITATION));
 }
}
