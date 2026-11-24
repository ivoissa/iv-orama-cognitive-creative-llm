package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic fixtures; no LLM needed and no claims about physical causality. */
class AgentDecisionVerifierTest {
 @TempDir Path root;
 private ReasonedObserverSession.Plan model() {
  return new ReasonedObserverSession.Plan("decision",true,
          Map.of("postgres",Map.of("db",true,"net",true),"network",Map.of("db",false,"net",false)),
          List.of(new ReasonedObserverSession.Probe("db","db.txt",1,40,"db=yes","db=no"),
                  new ReasonedObserverSession.Probe("net","net.txt",1,40,"net=yes","net=no")));
 }
 private ReasonedObserverSession.Result run(InvestigationOptions.Goal goal)throws Exception {
  return new ReasonedObserverSession(new ReadOnlyWorkspace(root),model(),null,
       new InvestigationOptions(goal,true,true,true)).investigate("Quelle cause sous ce modèle ?");
 }
 private AgentDecisionVerifier.Verdict verify(ReasonedObserverSession.Result r,AgentDecisionVerifier.Kind k,String cause,
                                             boolean complete,String... ids){
  return AgentDecisionVerifier.verify(model(),r,new AgentDecisionVerifier.Proposal(k,cause,List.of(ids),complete));
 }
 @Test void correctCauseIsConditionallySupportedWithoutClaimingEmpiricalTruth()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.DIAGNOSE);
  assertEquals(State.PROVEN_CONDITIONALLY,result.finding().state());
  var verdict=verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F1");
  assertEquals(AgentDecisionVerifier.Status.SUPPORTED_CONDITIONALLY,verdict.status());
  assertEquals(List.of("F1"),verdict.verifiedEvidenceIds());
 }
 @Test void wrongCauseAndUnknownCauseAreRejected()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.DIAGNOSE);
  assertTrue(verify(result,AgentDecisionVerifier.Kind.CAUSE,"network",false,"F1").errors().contains(AgentDecisionVerifier.Error.INCORRECT_CAUSE));
  assertTrue(verify(result,AgentDecisionVerifier.Kind.CAUSE,"myth",false,"F1").errors().contains(AgentDecisionVerifier.Error.UNKNOWN_CAUSE));
 }
 @Test void unjustifiedAbstentionAndFalseContradictionAreDetected()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.DIAGNOSE);
  assertTrue(verify(result,AgentDecisionVerifier.Kind.INDETERMINATE,"",false).errors().contains(AgentDecisionVerifier.Error.UNJUSTIFIED_ABSTENTION));
  assertTrue(verify(result,AgentDecisionVerifier.Kind.INCONSISTENT,"",false,"F1").errors().contains(AgentDecisionVerifier.Error.UNJUSTIFIED_CONTRADICTION));
 }
 @Test void inventedCitationAndDuplicateCitationAreRejected()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.DIAGNOSE);
  assertTrue(verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F3").errors().contains(AgentDecisionVerifier.Error.UNSUPPORTED_CITATION));
  assertTrue(verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F1","F1").errors().contains(AgentDecisionVerifier.Error.DUPLICATE_CITATION));
 }
 @Test void sourceThatDoesNotSupportTheClaimCannotBeCited()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.AUDIT_ALL);
  var verdict=verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F2");
  assertTrue(verdict.errors().contains(AgentDecisionVerifier.Error.NO_SUPPORTING_WITNESS));
  assertTrue(verdict.errors().contains(AgentDecisionVerifier.Error.MISSING_SUPPORT_FOR_CLAIM));
 }
 @Test void noEvidenceMeansNoValidatedCause()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes_extra\n");Files.writeString(root.resolve("net.txt"),"net=yes_extra\n");
  var result=run(InvestigationOptions.Goal.AUDIT_ALL);
  assertEquals(State.UNDETERMINED,result.finding().state());
  assertEquals(AgentDecisionVerifier.Status.INSUFFICIENT_EVIDENCE,
          verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false).status());
  var abstention=verify(result,AgentDecisionVerifier.Kind.INDETERMINATE,"",false);
  assertTrue(abstention.accepted());
  assertEquals(AgentDecisionVerifier.Status.ABSTENTION_COMPATIBLE,abstention.status());
 }
 @Test void contradictionsSuspendAnyCausalDecision()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\ndb=no\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.AUDIT_ALL);
  assertEquals(State.INCONSISTENT,result.finding().state());
  assertTrue(verify(result,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F1").errors().contains(AgentDecisionVerifier.Error.CONTRADICTORY_EVIDENCE));
  assertTrue(verify(result,AgentDecisionVerifier.Kind.INCONSISTENT,"",false,"F1").accepted());
 }
 @Test void fullAuditMustActuallyBeComplete()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n".repeat(280));
  var incomplete=run(InvestigationOptions.Goal.AUDIT_ALL);
  assertFalse(incomplete.completion().complete());
  assertTrue(verify(incomplete,AgentDecisionVerifier.Kind.CAUSE,"postgres",true,"F1").errors().contains(AgentDecisionVerifier.Error.AUDIT_NOT_COMPLETE));
  assertTrue(verify(incomplete,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F1").accepted());
 }
 @Test void completedAuditIsAcceptedOnlyWithActualWitness()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var complete=run(InvestigationOptions.Goal.AUDIT_ALL);
  assertTrue(complete.completion().complete());
  assertTrue(verify(complete,AgentDecisionVerifier.Kind.CAUSE,"postgres",true,"F1").accepted());
 }
 @Test void forgedOrMismatchedObservationCannotBeCertified()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"neutral\n");
  var result=run(InvestigationOptions.Goal.DIAGNOSE);
  var forged=new ReasonedObserverSession.Result(result.report(),result.finding(),result.steps(),List.of(),
          result.commentaryError(),result.completion());
  assertEquals(AgentDecisionVerifier.Status.INTEGRITY_ERROR,
          verify(forged,AgentDecisionVerifier.Kind.CAUSE,"postgres",false,"F1").status());
 }
 @Test void contradictoryDistinctSignalsAreRejectedAndReviewModelAbstains()throws Exception{
  Files.writeString(root.resolve("db.txt"),"db=yes\n");Files.writeString(root.resolve("net.txt"),"net=no\n");
  var result=run(InvestigationOptions.Goal.REVIEW_MODEL);
  assertEquals(State.INCONSISTENT,result.finding().state());
  assertTrue(verify(result,AgentDecisionVerifier.Kind.INCONSISTENT,"",false,"F1","F2").accepted());
  assertFalse(verify(result,AgentDecisionVerifier.Kind.INDETERMINATE,"",false,"F1","F2").accepted());
 }
}
