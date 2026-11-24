package com.ivorama.nlp.transform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NeighborPolicyTest {
 @Test void strictNeverAdmitsNeighbors(){assertEquals("STRICT_ROBERT_ONLY",NeighborPolicy.reason(0,.9,.9,1,.9,.8,false,false));}
 @Test void freedomChangesAdmission(){assertNotEquals("ELIGIBLE",NeighborPolicy.reason(.25,.15,.3,.9,.3,.65,false,false));assertEquals("ELIGIBLE",NeighborPolicy.reason(1,.15,.3,.9,.3,.2,false,false));}
 @Test void knownContradictionsAndDuplicatesRemainExcluded(){assertEquals("FORBIDDEN_LEXICAL_RELATION",NeighborPolicy.reason(1,.9,.9,1,.9,.2,false,true));assertEquals("CANDIDATE_ALREADY_IN_CONTEXT",NeighborPolicy.reason(1,.9,.9,1,.9,.2,true,false));}
 @Test void missingContextNeverAuthorizes(){assertEquals("NO_CONTEXT_SUPPORT",NeighborPolicy.reason(1,.8,0,0,0,.2,false,false));}
 @Test void contextLossStillBlocks(){assertEquals("CONTEXT_DEGRADATION",NeighborPolicy.reason(1,.8,.01,.01,.9,.2,false,false));}
}
