package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
class FunctionalCompatibilityTest {
 static final FrenchNlpAnalyzer analyzer=new FrenchNlpAnalyzer();
 static final LexiconMorphologicalEngine morph=new LexiconMorphologicalEngine();
 static final RobertGroupEvidence evidence=RobertGroupEvidence.load();
 @Test void numericUnit(){var a=analyzer.analyzeFirstSentence("Il est plus de onze heures.");var t=a.tokens().stream().filter(x->x.word().equals("heures")).findFirst().orElseThrow();assertTrue(LexicalSafety.isProtectedExpression(a,t));}
 private String reason(String sentence,String word,String candidate){var a=analyzer.analyzeFirstSentence(sentence);var t=a.tokens().stream().filter(x->x.word().equals(word)).findFirst().orElseThrow();return FunctionalCompatibility.reason(a,t,word,candidate,"",evidence,morph);}
 @Test void humanVersusEvent(){assertFalse(reason("Sa sœur lisait un roman.","sœur","noce").isEmpty());}
 @Test void creativeAnimal(){assertEquals("",reason("Il craignait le loup.","loup","renard"));}
 @Test void garmentVersusCooking(){assertFalse(reason("Une robe blanche reposait sur le lit.","blanche","cuit").isEmpty());}
 @Test void spatialCategory(){assertFalse(reason("Il travaille dans la cuisine.","cuisine","gastronomie").isEmpty());}
}
