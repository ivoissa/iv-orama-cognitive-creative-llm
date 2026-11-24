package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
/** Native analyses: movement/option assertions do not use fabricated dependency graphs. */
class NativeModesStabilityTest {
 static FrenchNlpAnalyzer analyzer;static SynonymProvider provider;
 @BeforeAll static void init(){analyzer=new FrenchNlpAnalyzer();provider=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/candidate.yml"));}
 TransformationResult run(String text,TransformationConfig c){return new SentenceTransformerEngine(provider).transform(analyzer.analyzeFirstSentence(text),c);}
 TransformationConfig mode(boolean yoda,boolean cc,boolean stoic){return new TransformationConfig(false,true,false,yoda,false,cc,true,stoic,42,true);}
 @Test void dativeRecipientIsNotMovedAsCircumstantial(){var a=analyzer.analyzeFirstSentence("Le médecin donne le médicament au patient.");assertTrue(a.objects().stream().anyMatch(c->c.role()==ConstituentRole.INDIRECT_OBJECT));assertTrue(a.circumstantials().isEmpty());}
 @Test void nativeCodCoiYoda(){var r=run("Le chat donne la pâtée au chien.",mode(true,false,false));assertTrue(r.appliedRules().contains("yoda_mode"),r.toString());assertEquals("La pâtée au chien le chat donne.",r.transformed());}
 @Test void nativeCircumstantialMovement(){var r=run("Le chat mange dans la cuisine.",mode(false,true,false));assertEquals("Dans la cuisine, le chat mange.",r.transformed());}
 @Test void combinedMovementKeepsPunctuationNegationAndCliticScope() {
  var all = new TransformationConfig(false, true, false, true, true, true, true, false, 42, true, true);
  var service = new SentenceTransformerService(analyzer,
      SynonymProviderFactory.create(TransformationConfigLoader.defaultPath()));
  String input = "Le chat mange la pâtée dans la cuisine. Le chat ne mange pas la pâtée dans la cuisine. Marie lui donne le livre dans la cour.";
  assertEquals("Dans la cuisine, la pâtée le chat mange. Le chat ne mange pas la pâtée dans la cuisine. Dans la cour, le livre Marie lui donne.",
      service.transformText(input, all).transformed());
 }
 @Test void stoicDisablesLexicalAndMovementEdits(){var r=run("Le médecin examine le patient dans son cabinet.",mode(true,true,true));assertTrue(r.lexicalAttempts().isEmpty());assertFalse(r.appliedRules().contains("yoda_mode"));assertFalse(r.appliedRules().contains("joyeuse_circonstance_mode"));assertTrue(r.transformed().contains("Le médecin"));}
 @Test void firstAndThirdCommonNounsOnly(){var r=run("Le médecin montre une image au patient.",new TransformationConfig(false,true,false,false,false,false,false,false,42,true));assertEquals(List.of("médecin","patient"),r.lexicalAttempts().stream().filter(a->a.pos().equals("NOUN")).map(LexicalAttempt::word).toList());}
 @Test void requestedRegistersAreRetainedWithoutInventingCandidates(){for(SynonymRegister reg:SynonymRegister.values()){var c=new TransformationConfig(reg==SynonymRegister.FAMILIER,reg==SynonymRegister.COURANT,reg==SynonymRegister.SOUTENU,false,false,false,false,false,42,true);var r=run("Le médecin examine le patient dans son cabinet.",c);assertEquals(reg,r.effectiveRegister());assertEquals(reg,r.requestedRegister());}}
 @Test void cacheCannotInjectAContextFreeCandidate(@TempDir Path dir)throws Exception{Path cache=dir.resolve("cache.tsv");Files.writeString(cache,"médecin\tNOUN\tCOURANT\tfroid\n");var wrapped=new CachingSynonymProvider(provider,cache,true,false);String text="Le médecin examine le patient dans son cabinet.";var a=analyzer.analyzeFirstSentence(text);var c=mode(false,false,false);assertEquals(new SentenceTransformerEngine(provider).transform(a,c).transformed(),new SentenceTransformerEngine(wrapped).transform(a,c).transformed());assertFalse(new SentenceTransformerEngine(wrapped).transform(a,c).transformed().contains("froid"));}
 @Test void quotedTextRemainsExact(){String s="« La bicyclette possède deux roues et des pédales. »";assertEquals(s,run(s,mode(false,false,false)).transformed());}
 @Test void protectedProperNameIsNotEdited(){String s="Marie observe une bicyclette dans la cour.";assertTrue(run(s,mode(false,false,false)).transformed().startsWith("Marie"));}
 @Test void locativeIsNotReclassifiedAsRecipient(){var a=analyzer.analyzeFirstSentence("Le médecin donne un livre à Paris.");assertFalse(a.objects().stream().filter(c->c.role()==ConstituentRole.INDIRECT_OBJECT).findAny().isPresent());}
 @Test void independentLexicalFactsAreLoaded(){var m=new LexiconMorphologicalEngine();assertTrue(m.attestedIndirectVerb("donne"));assertTrue(m.attestedAnimateNoun("chien"));assertFalse(m.attestedAnimateNoun("cuisine"));assertFalse(m.attestedAnimateNoun("zzinconnu"));}
 @Test void inverseOnlyEntryDoesNotAuthorizeSenseOrRegister(){var d=new RobertDisambiguator(RobertGroupEvidence.load(),index());assertFalse(d.assess("ami","n","copain",List.of("tristesse","musicien","inquiéter")).allowed());}
 private RobertCandidateIndex index(){try{return RobertCandidateIndex.load();}catch(Exception e){throw new IllegalStateException(e);}}
}
