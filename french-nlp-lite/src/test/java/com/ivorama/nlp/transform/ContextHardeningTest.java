package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class ContextHardeningTest {
 static FrenchNlpAnalyzer analyzer; static LexiconMorphologicalEngine morph;
 @BeforeAll static void setup(){analyzer=new FrenchNlpAnalyzer();morph=new LexiconMorphologicalEngine();}
 @Test void incompatibleComplementAndParticiples(){var a=analyzer.analyzeFirstSentence("Je me sentais plein de courage.");var t=a.tokens().stream().filter(x->x.word().equals("plein")).findFirst().orElseThrow();assertFalse(ConstructionGuard.compatible(a,t,"véritable",morph));assertTrue(ConstructionGuard.compatible(a,t,"emplir",morph));assertTrue(ConstructionGuard.compatible(a,t,"remplir",morph));}
 @Test void masculineOriginalAgreementOverridesWrongHead(){var a=analyzer.analyzeFirstSentence("Il fit un signe de tête amical.");var t=a.tokens().stream().filter(x->x.word().equals("amical")).findFirst().orElseThrow();var words=new HashMap<Integer,String>();a.tokens().forEach(x->words.put(x.index(),x.word()));var plan=new NominalAgreement(morph).plan(a,t,"convivial",words,false).orElseThrow();assertEquals("convivial",plan.words().get(t.index()));}
 @Test void punctuationFrench(){assertEquals("Il part ; elle reste.",SentenceRenderer.render(List.of("Il","part",";","elle","reste",".")));}
}
