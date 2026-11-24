package fr.ivorama.cognitive.agent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Audit712UnavailableSourceTest {
 @TempDir Path root;
 private ReasonedObserverSession.Plan plan(String path){return new ReasonedObserverSession.Plan("bounded",true,Map.of("alpha",Map.of("a",true),"beta",Map.of("a",false)),List.of(new ReasonedObserverSession.Probe("a",path,1,40,"a=yes","a=no")));}
 @Test void oversizedAllowedFileDoesNotAbortAndNeverCreatesAFact()throws Exception{
  Files.writeString(root.resolve("a.txt"),"x\n".repeat(50000));
  var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan("a.txt"),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true)).investigate("Audit ?");
  assertEquals("UNDETERMINED",r.finding().state().name());assertEquals(List.of("a"),r.completion().unavailableSignals());assertFalse(r.completion().complete());assertTrue(r.facts().isEmpty());assertEquals(1,r.report().toolCalls());
 }
 @Test void binaryAllowedFileIsUnavailableAndDoesNotBlockOtherProbes()throws Exception{
  Files.write(root.resolve("a.txt"),new byte[]{0,1,2});
  var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan("a.txt"),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true)).investigate("Audit ?");
  assertEquals(List.of("a"),r.completion().unavailableSignals());assertTrue(r.facts().isEmpty());
 }
 @Test void traversalPolicyStillFailsClosed()throws Exception{
  assertThrows(SecurityException.class,()->new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan("../a.txt"),null).investigate("Audit ?"));
 }
}
