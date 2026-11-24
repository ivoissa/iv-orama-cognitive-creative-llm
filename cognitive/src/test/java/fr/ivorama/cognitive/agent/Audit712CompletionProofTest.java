package fr.ivorama.cognitive.agent;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class Audit712CompletionProofTest {
 @TempDir Path root;
 ReasonedObserverSession.Plan plan(){return new ReasonedObserverSession.Plan("complete",true,Map.of("alpha",Map.of("a",true,"b",true),"beta",Map.of("a",false,"b",false)),List.of(new ReasonedObserverSession.Probe("a","a.txt",1,40,"a=yes","a=no"),new ReasonedObserverSession.Probe("b","b.txt",1,40,"b=yes","b=no")));}
 void files()throws Exception{Files.writeString(root.resolve("a.txt"),"a=yes\n");Files.writeString(root.resolve("b.txt"),"x\n");}
 @Test void forgedCompleteFlagDoesNotCertifyAnUnvisitedProbe()throws Exception{
  files();var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null).investigate("Cause ?");assertEquals(1,r.report().toolCalls());
  var altered=new ReasonedObserverSession.Result(r.report(),r.finding(),r.steps(),r.facts(),r.commentaryError(),new ReasonedObserverSession.Completion(InvestigationOptions.Goal.AUDIT_ALL,true,List.of(),List.of(),List.of()));
  var v=AgentDecisionVerifier.verifyObservedDecision(plan(),altered);assertFalse(v.accepted());assertTrue(v.errors().contains(AgentDecisionVerifier.Error.AUDIT_NOT_COMPLETE));
 }
 @Test void genuineCompleteReadCoverageRemainsAccepted()throws Exception{
  files();var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true)).investigate("Audit ?");assertTrue(r.completion().complete());assertTrue(AgentDecisionVerifier.verifyObservedDecision(plan(),r).accepted());
 }
}
