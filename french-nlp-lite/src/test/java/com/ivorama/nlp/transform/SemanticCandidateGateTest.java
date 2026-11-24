package com.ivorama.nlp.transform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SemanticCandidateGateTest {
 @Test void sharedSynsetRequiresContext() {
  var gate=new SemanticCandidateGate();
  assertEquals(SemanticCandidateGate.Decision.REVIEW_CONTEXT,
   gate.assess(0.8,true,"POLYSEMY_NOT_RESOLVED:2_OMW_SENSES").decision());
 }
 @Test void grammarAndMultiwordRemainBlocked() {
  var gate=new SemanticCandidateGate();
  assertEquals(SemanticCandidateGate.Decision.REVIEW_MORPHOLOGY,
   gate.assess(1,true,"TARGET_PART_OF_SPEECH_OR_FORM_NOT_ATTESTED").decision());
  assertEquals(SemanticCandidateGate.Decision.REVIEW_MULTIWORD,
   gate.assess(1,true,"MULTIWORD_CANDIDATE_REQUIRES_PHRASE_REALIZATION").decision());
 }
 @Test void noEvidenceNeverPromotes() {
  assertEquals(SemanticCandidateGate.Decision.INSUFFICIENT_EVIDENCE,
   new SemanticCandidateGate().assess(0,false,"POLYSEMY_NOT_RESOLVED").decision());
 }
 @Test void invalidScoreRejected() {
  assertThrows(IllegalArgumentException.class,()->new SemanticCandidateGate().assess(Double.NaN,true,"x"));
 }
}
