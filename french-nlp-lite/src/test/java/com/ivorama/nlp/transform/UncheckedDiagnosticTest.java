package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import org.junit.jupiter.api.*;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class UncheckedDiagnosticTest {
 @Test void bareGenitiveLexicalUnitIsProtectedEvenUnchecked(){System.setProperty("ivorama.semantic.rejections.disabled","true");try{var p=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/user-all-modes.yml"));var a=new FrenchNlpAnalyzer().analyzeFirstSentence("Son état d'esprit change.");var c=new TransformationConfig(false,true,false,false,false,false,false,false,42,true);assertEquals("Son état d'esprit change.",new SentenceTransformerEngine(p).transform(a,c).transformed());}finally{System.clearProperty("ivorama.semantic.rejections.disabled");}}
 @Test void bypassIsExplicitAndNotASemanticScore(){System.setProperty("ivorama.semantic.rejections.disabled","true");try{var p=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/user-all-modes.yml"));var a=new FrenchNlpAnalyzer().analyzeFirstSentence("Mon père arrive.");var t=a.tokens().stream().filter(x->x.word().equals("père")).findFirst().orElseThrow();var candidates=p.inventory(t,SynonymRegister.COURANT,a);assertFalse(candidates.isEmpty());var d=p.assess(t,SynonymRegister.COURANT,candidates.get(0),a);assertTrue(d.allowed());assertEquals("SEMANTIC_REJECTIONS_DISABLED",d.reason());assertEquals(0,d.score());assertFalse(p.assess(t,SynonymRegister.COURANT,"zzinconnu",a).allowed());}finally{System.clearProperty("ivorama.semantic.rejections.disabled");}}
 @Test void normalProfileStillRequiresContext(){System.clearProperty("ivorama.semantic.rejections.disabled");var p=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/candidate.yml"));var t=new TokenData(1,"père","NOUN");assertFalse(p.assess(t,SynonymRegister.COURANT,"abbé",null).allowed());}
 @Test void directDictionaryUsesDictionaryOrderWhenSemanticRejectionsAreDisabled() {
  var provider = new SynonymProvider() {
   public java.util.Optional<String> synonym(TokenData token, SynonymRegister register) { return java.util.Optional.empty(); }
   public java.util.List<String> inventory(TokenData token, SynonymRegister register, SentenceAnalysis context) {
    return switch (token.word()) {
     case "vélo" -> java.util.List.of("bicyclette", "cycle");
     case "neuf" -> java.util.List.of("nouveau");
     default -> java.util.List.of();
    };
   }
   public CandidateDecision assessUnchecked(TokenData token, SynonymRegister register, String word, SentenceAnalysis context) {
    return inventory(token, register, context).contains(word)
        ? CandidateDecision.trusted() : CandidateDecision.reject("OUTSIDE_INVENTORY");
   }
   public CandidateDecision assess(TokenData token, SynonymRegister register, String word, SentenceAnalysis context) {
    return new CandidateDecision(false, "UNCERTAIN", word.equals("bicyclette") ? .8 : .3, "", "fixture");
   }
  };
  var sentence = new SentenceAnalysis("Un vélo neuf roule.", java.util.List.of(
      new TokenData(1, "Un", "DET", 0, 2, "", "", "", 2, "det"),
      new TokenData(2, "vélo", "NOUN", 3, 7, "", "", "", 4, "nsubj"),
      new TokenData(3, "neuf", "ADJ", 8, 12, "", "", "", 2, "amod"),
      new TokenData(4, "roule", "VERB", 13, 18, "", "", "", 0, "root")), 4, null, null, null);
  var config = new TransformationConfig(false,true,false,false,true,false,false,false,42,true,false,java.util.List.of(),true,1,100,100,false);
  var result = new SentenceTransformerEngine(provider).transform(sentence, config);
  assertEquals("Une bicyclette nouvelle roule.", result.transformed());
  assertEquals(2, result.lexicalAttempts().stream().filter(a -> a.reason().equals("DIRECT_DICTIONARY")).count());
 }
 @Test void allModesExceptStoicAreEnabled(){var c=TransformationConfigLoader.load(Path.of("src/test/resources/profiles/user-all-modes.yml"));assertTrue(c.yodaMode());assertTrue(c.joyeuseCirconstanceMode());assertTrue(c.respirationCoolMode());assertTrue(c.emphaseColores());assertTrue(c.replaceNounsEnabled());assertTrue(c.synonymeFamilierEnergie());assertTrue(c.synonymeCourantEnergie());assertTrue(c.synonymeSoutenuEnergie());assertFalse(c.stoiquePhiloMode());}
}
