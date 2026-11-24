package com.ivorama.nlp.transform;
import org.junit.jupiter.api.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class IndividualCandidateScoreTest{
 RobertDisambiguator selector(){try{return new RobertDisambiguator(RobertGroupEvidence.load(),RobertCandidateIndex.load());}catch(Exception e){throw new IllegalStateException(e);}}
 @Test void candidateWordsAreActuallyScoredIndependently(){var s=selector();var ctx=List.of("roue","pédale");var bicycle=s.candidateContextScore("bicyclette","n","vélo",ctx,"");var ghost=s.candidateContextScore("bicyclette","n","fantôme",ctx,"");assertTrue(bicycle.evaluated());assertTrue(ghost.evaluated());assertTrue(bicycle.value()>ghost.value(),bicycle+" / "+ghost);assertEquals((bicycle.direct()+bicycle.neighborhood())/2,bicycle.value(),1e-12);}
 @Test void missingCandidateSenseIsExplicitlyUnavailable(){var v=selector().candidateContextScore("père","n","zzinconnu",List.of("famille"),"");assertFalse(v.evaluated());assertEquals(0,v.value());}
 @Test void identifiedReadingIsUsedWhenShared(){var v=selector().candidateContextScore("médecin","n","docteur",List.of("patient","cabinet"),"10020890-n");assertTrue(v.evaluated());assertEquals("10020890-n",v.sense());assertTrue(v.value()>=0&&v.value()<=1);}
 @Test void fitDoesNotBypassSourceSenseRejection(){var d=selector().assess("père","n","géniteur",List.of("zzinconnu"));assertFalse(d.allowed());assertTrue(d.evidence().contains("candidate_context_score="));assertTrue(d.evidence().contains("source_score="));}
}
