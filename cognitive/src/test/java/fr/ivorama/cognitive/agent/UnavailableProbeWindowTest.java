package fr.ivorama.cognitive.agent;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class UnavailableProbeWindowTest {
 @TempDir Path root;
 ReasonedObserverSession.Plan plan(){return new ReasonedObserverSession.Plan("short_source",true,Map.of("alpha",Map.of("a",true,"b",true),"beta",Map.of("a",false,"b",false)),List.of(new ReasonedObserverSession.Probe("a","a.txt",50,10,"a=yes","a=no"),new ReasonedObserverSession.Probe("b","b.txt",1,10,"b=yes","b=no")));}
 @Test void truncatedSourceRemainsUnknownAndOtherEvidenceIsUsed()throws Exception {
  Files.writeString(root.resolve("a.txt"),"x\n");Files.writeString(root.resolve("b.txt"),"b=no\n");
  for(var policy:InvestigationOptions.ProbePolicy.values())for(var goal:List.of(InvestigationOptions.Goal.AUDIT_ALL,InvestigationOptions.Goal.REVIEW_MODEL)){
   var options=new InvestigationOptions(goal,true,true,policy==InvestigationOptions.ProbePolicy.COVERAGE_FIRST,policy);var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null,options).investigate("Audit ?");
   assertEquals("PROVEN_CONDITIONALLY",r.finding().state().name());assertEquals("beta",r.finding().cause());assertFalse(r.completion().complete());assertTrue(r.completion().unavailableSignals().contains("a"));assertTrue(r.facts().stream().noneMatch(f->f.signal().equals("a")));assertEquals(2,r.report().toolCalls());assertTrue(AgentDecisionVerifier.verifyObservedDecision(plan(),r).accepted());
  }
 }
 @Test void noWitnessDoesNotBecomeNegativeFact()throws Exception {
  Files.writeString(root.resolve("a.txt"),"");Files.writeString(root.resolve("b.txt"),"x\n");var r=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true,false,InvestigationOptions.ProbePolicy.BALANCED)).investigate("Audit ?");assertEquals("UNDETERMINED",r.finding().state().name());assertTrue(r.facts().isEmpty());assertFalse(r.completion().complete());
 }
 @Test void publicArgumentAndSecurityContractsRemainDistinct()throws Exception {
  Files.writeString(root.resolve("a.txt"),"x\n");var ws=new ReadOnlyWorkspace(root);assertThrows(IllegalArgumentException.class,()->ws.read("a.txt",50,10));assertThrows(SecurityException.class,()->ws.read("../a.txt",1,10));assertThrows(IllegalArgumentException.class,()->ws.read("a.txt",0,10));
 }
}
