package com.ivorama.nlp.transform;
import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class CreativeSelectionTest {
 private DiscoGroupSynonymProvider.Detail d(String name,double score,String reason){return new DiscoGroupSynonymProvider.Detail(name,"g",1,1,1,1,score,reason);}
 @Test void maximumFreedomDoesNotForceWorst(){var list=List.of(d("a",.9,"ELIGIBLE"),d("b",.1,"ELIGIBLE"));assertEquals("a",CreativeSelection.order(list,1).get(0).candidate());}
 @Test void excludesRejectedAndDeduplicates(){var list=List.of(d("a",.9,"ELIGIBLE"),d("a",.8,"ELIGIBLE"),d("b",.7,"ELIGIBLE"),d("x",1,"FORBIDDEN_LEXICAL_RELATION"));assertEquals(List.of("a","b"),CreativeSelection.order(list,1).stream().map(DiscoGroupSynonymProvider.Detail::candidate).toList());}
 @Test void distanceInfluencesSelection(){var near=new DiscoGroupSynonymProvider.Detail("near","g",1,.95,1,1,.8,"ELIGIBLE");var far=new DiscoGroupSynonymProvider.Detail("far","g",1,.3,1,1,.8,"ELIGIBLE");assertEquals(near,CreativeSelection.order(List.of(near,far),.25).get(0));assertEquals(far,CreativeSelection.order(List.of(near,far),1).get(0));}
 @Test void expandsGroupsProgressively(){assertFalse(CreativeSelection.groupAllowed(.6,1,.25));assertTrue(CreativeSelection.groupAllowed(.6,1,.75));assertFalse(CreativeSelection.groupAllowed(0,1,1));}
 @Test void emptyAndSingleton(){assertTrue(CreativeSelection.order(List.of(),1).isEmpty());var one=List.of(d("a",.8,"ELIGIBLE"));assertEquals(one,CreativeSelection.order(one,1));}
}
