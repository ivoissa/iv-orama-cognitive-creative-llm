package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Reasoned077RecoveryTest {
 @TempDir Path root;
 @Test void missingProbeDoesNotBecomeFalseAndAnAlternativeCanResolveTheQuestion() throws Exception {
  Files.writeString(root.resolve("backup.txt"),"backup=yes\n");
  var plan=new ReasonedObserverSession.Plan("recovery",true,
    Map.of("a",Map.of("a_primary",true,"z_backup",true),"b",Map.of("a_primary",false,"z_backup",false)),
    List.of(new ReasonedObserverSession.Probe("a_primary","missing.txt",1,40,"primary=yes","primary=no"),
      new ReasonedObserverSession.Probe("z_backup","backup.txt",1,40,"backup=yes","backup=no")));
  var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan,null).investigate("Diagnostic ?");
  assertEquals(State.PROVEN_CONDITIONALLY,r.finding().state());assertEquals("a",r.finding().cause());
  assertEquals(List.of("UNAVAILABLE","TRUE"),r.steps().stream().map(ReasonedObserverSession.Step::result).toList());
  assertEquals(1,r.facts().size());assertEquals("z_backup",r.facts().getFirst().signal());
  assertTrue(r.report().answer().contains("lecture indisponible"));
 }
 @Test void allMissingLeavesTheQuestionOpen()throws Exception {
  var plan=new ReasonedObserverSession.Plan("unavailable",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),
    List.of(new ReasonedObserverSession.Probe("s","missing.txt",1,40,"s=yes","s=no")));
  var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan,null).investigate("Diagnostic ?");
  assertEquals(State.UNDETERMINED,r.finding().state());assertTrue(r.facts().isEmpty());
  assertTrue(r.report().answer().contains("ne suffisent pas"));assertEquals(1,r.report().toolCalls());
 }
 @Test void securityErrorsAreNeverReclassifiedAsOrdinaryMissingData()throws Exception {
  var plan=new ReasonedObserverSession.Plan("unsafe",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),
    List.of(new ReasonedObserverSession.Probe("s","../outside.txt",1,40,"s=yes","s=no")));
  assertThrows(SecurityException.class,()->new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan,null).investigate("Diagnostic ?"));
 }
}
