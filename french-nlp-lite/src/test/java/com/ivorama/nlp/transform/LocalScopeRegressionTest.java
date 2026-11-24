package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import org.junit.jupiter.api.*;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class LocalScopeRegressionTest{
 static FrenchNlpAnalyzer analyzer;static SynonymProvider provider;static TransformationConfig c;
 @BeforeAll static void init(){analyzer=new FrenchNlpAnalyzer();provider=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/user-all-modes.yml"));c=TransformationConfigLoader.load(Path.of("src/test/resources/profiles/user-all-modes.yml"));}
 @Test void ellipsisSeparatesFiniteSentenceAndPreservesOffsets(){String s="Il hésite… Elle arrive.";var a=analyzer.analyze(s);assertEquals(2,a.size());for(var x:a)assertEquals(x.originalText(),s.substring(x.beginOffset(),x.endOffset()));assertEquals(a.get(0),analyzer.analyzeFirstSentence(s));}
 @Test void asciiEllipsisAlsoSeparates(){assertEquals(2,analyzer.analyze("Il hésite... Elle arrive.").size());}
 @Test void properNameAfterEllipsisDoesNotForceNewSentence(){assertEquals(1,analyzer.analyze("Il pense à… Marie.").size());}
 @Test void quotedEllipsisIsNotForciblySplit(){String text="« Il hésite… Elle arrive. »";assertEquals(text,new SentenceTransformerService(analyzer,(t,r)->java.util.Optional.empty()).transformText(text,c).transformed());}
 @Test void degreeInsideNominalComplementDoesNotBlockMatrix()throws Exception{String text=Files.readString(Path.of("evaluation/user-text/original.txt"));var r=new SentenceTransformerService(analyzer,provider).transformText(text,c);assertEquals(5,r.sentences().size());assertEquals("Malgré le peu de temps que j'ai eu avec lui, un souvenir marquant mon père m'a laissé.",r.sentences().get(0).transformed());assertTrue(r.sentences().get(0).appliedRules().contains("yoda_mode"));assertTrue(r.sentences().get(0).appliedRules().contains("joyeuse_circonstance_mode"));assertTrue(r.sentences().get(4).appliedRules().contains("yoda_mode"));}
 @Test void trueMatrixNegationRemainsProtected(){var a=analyzer.analyzeFirstSentence("Le garçon ne mange pas la pomme.");var r=new SentenceTransformerEngine(provider).transform(a,c);assertFalse(r.appliedRules().contains("yoda_mode"));assertTrue(r.transformed().contains("ne mange pas"));}
 @Test void cliticRecipientStaysWithVerb(){var a=analyzer.analyzeFirstSentence("Elle m'offre un cadeau.");var r=new SentenceTransformerEngine(provider).transform(a,c);assertTrue(r.appliedRules().contains("yoda_mode"),r.toString());assertTrue(r.transformed().contains("m'offre"),r.transformed());}
}
