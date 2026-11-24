package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContextualGuardTest {
 @Test void nominalAttachment(){var a=new SentenceAnalysis("Les événements d’hier arrivent",List.of(new TokenData(1,"événements","NOUN"),new TokenData(2,"hier","NOUN",-1,-1,"hier","","",1,"nmod"),new TokenData(3,"arrivent","VERB")),3,null,List.of(),List.of());assertTrue(ContextualGuard.nominalModifier(a,Constituent.of(ConstituentRole.CIRCUMSTANTIAL,"nmod",List.of(2))));}
 @Test void introducedCollision(){var a=new FrenchNlpAnalyzer().analyzeFirstSentence("Une application Java analyse le message.");var t=a.tokens().stream().filter(x->x.word().equals("application")).findFirst().orElseThrow();var words=new HashMap<Integer,String>();a.tokens().forEach(x->words.put(x.index(),x.word()));assertTrue(ContextualGuard.introducesDuplicate(a,t,"analyse",words,new LexiconMorphologicalEngine()));assertFalse(ContextualGuard.introducesDuplicate(a,t,"programme",words,new LexiconMorphologicalEngine()));}
}
