package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import org.junit.jupiter.api.*;import org.junit.jupiter.api.condition.EnabledIfSystemProperty;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
/** Actual official French DISCO index, not a synthetic similarity oracle. */
@EnabledIfSystemProperty(named="ivorama.test.disco",matches="true")
class DiscoIntegrationTest{
 @Test void explicitZeroBlocksNounAndAdjectiveEditsWithModesEnabled() throws Exception {
  var global = TransformationConfigLoader.loadGlobal(TransformationConfigLoader.defaultPath());
  var d = global.lexical().disco();
  var settings = new DiscoSettings(true, d.directory(), d.minGroupScore(), d.minMargin(),
      d.minRetention(), d.semanticFreedom(), 0, 0);
  var engine = new SentenceTransformerEngine(new DiscoGroupSynonymProvider(settings));
  for (String file : java.util.List.of("T01.txt", "T02.txt", "T08.txt")) {
   String text = Files.readString(Path.of("validation/corpus", file)).strip();
   var result = SentenceTransformerService.transformAnalyses(text, analyzer.analyze(text),
       global.transformations(), engine);
   assertTrue(result.sentences().stream().allMatch(sentence -> sentence.replacements().isEmpty()), file);
   if (file.equals("T01.txt")) assertNotEquals(text, result.transformed());
  }
 }
 @Test void preparedContextCosineMatchesUpstreamDisco() {
  var space = new DiscoWordSpace(Path.of("data/disco/fr-general-20151126-lm-sim"));
  var weights = new java.util.TreeMap<String,Double>();
  weights.put("carnet", 0.4);
  weights.put("note", 0.7);
  weights.put("écrire", 0.2);
  var original = space.prototype(weights);
  var prepared = space.contextVector(weights);
  for (String word : java.util.List.of("papier", "écrire", "chanson", "zzmotinconnu")) {
   var vector = space.vector(word);
   assertEquals(space.cosine(vector, original), space.cosine(vector, prepared), word);
  }
 }
 @Test void creativeFreedomChangesRealTextAndPreservesProtectedMaterial() throws Exception {
  String text = Files.readString(Path.of("evaluation/user-text/original.txt")).stripTrailing();
  var creative = SynonymProviderFactory.create(Path.of("src/test/resources/profiles/disco-freedom-75.yml"));
  String output = new SentenceTransformerService(analyzer, creative).transformText(text, config).transformed();
  assertNotEquals(run(text), output);
  assertTrue(output.contains("Tiebile"));
  assertTrue(output.contains("état d'esprit"));
  assertFalse(output.contains("amidonné"));
  assertTrue(output.contains("trois frères"));
  assertFalse(output.contains("trois amis"));
  assertFalse(output.contains("ce lignage"));
 }
 @Test void mareiAdjectiveFreedomProducesRealLexicalVariation()throws Exception{
  String text="Je partis, en me retournant presque tous les dix pas. Toujours je vis Mareï qui me suivait de l’œil, et chaque fois il me fit un signe de tête amical. J’avoue que j’avais alors un peu honte de ma peur ; toutefois je craignais encore vaguement le loup. Quand j’eus refranchi le ravin, l’épouvante disparut brusquement ; mon chien Voltschok bondit vers moi, venant de je ne sais où, et avec mon chien je me sentais plein de courage.";
  String yaml=Files.readString(TransformationConfigLoader.defaultPath());
  Path temp=Files.createTempFile("ivorama-marei-",".yml");
  try{String base=yaml.replaceAll("noun_semantic_freedom: [0-9]+","noun_semantic_freedom: 0").replace("replace_first_and_third_nouns_enabled: true","replace_first_and_third_nouns_enabled: false");
   Files.writeString(temp,base.replaceAll("adjective_semantic_freedom: [0-9]+","adjective_semantic_freedom: 0"));
   var low=new SentenceTransformerService(analyzer,SynonymProviderFactory.create(temp)).transformText(text,TransformationConfigLoader.load(temp));
   Files.writeString(temp,base.replaceAll("adjective_semantic_freedom: [0-9]+","adjective_semantic_freedom: 100"));
   var high=new SentenceTransformerService(analyzer,SynonymProviderFactory.create(temp)).transformText(text,TransformationConfigLoader.load(temp));
   assertNotEquals(low.transformed(),high.transformed(),()->high.sentences().toString());
   assertTrue(high.sentences().stream().flatMap(x->x.replacements().stream()).anyMatch(x->x.reason().contains("adjectif")));
   assertTrue(high.transformed().contains("Mareï"));assertTrue(high.transformed().contains("Voltschok"));
  }finally{Files.deleteIfExists(temp);}
 }
 @Test void cacheDoesNotChangeCompleteTransformation() {
  var global = TransformationConfigLoader.loadGlobal(TransformationConfigLoader.defaultPath());
  var d = global.lexical().disco();
  String text = "Une automobile traverse la place.";
  var analyses = analyzer.analyze(text);
  String previous = System.getProperty("ivorama.cache.disabled");
  try {
   for (int freedom : new int[]{0, 50, 100}) {
    var settings = new DiscoSettings(true, d.directory(), d.minGroupScore(), d.minMargin(),
        d.minRetention(), d.semanticFreedom(), freedom, freedom);
    System.setProperty("ivorama.cache.disabled", "true");
    var uncached = SentenceTransformerService.transformAnalyses(text, analyses, global.transformations(),
        new SentenceTransformerEngine(new DiscoGroupSynonymProvider(settings)));
    System.setProperty("ivorama.cache.disabled", "false");
    var engine = new SentenceTransformerEngine(new DiscoGroupSynonymProvider(settings));
    var cached = SentenceTransformerService.transformAnalyses(text, analyses, global.transformations(), engine);
    assertEquals(uncached, cached);
    assertEquals(cached, SentenceTransformerService.transformAnalyses(text, analyses, global.transformations(), engine));
    boolean explored = cached.sentences().stream().flatMap(sentence -> sentence.lexicalAttempts().stream())
        .anyMatch(attempt -> !attempt.candidates().isEmpty());
    assertEquals(freedom > 0, explored, "lexical candidates at " + freedom);
   }
  } finally {
   if (previous == null) System.clearProperty("ivorama.cache.disabled");
   else System.setProperty("ivorama.cache.disabled", previous);
  }
 }
 static DiscoGroupSynonymProvider provider;static FrenchNlpAnalyzer analyzer;static TransformationConfig config;
 @BeforeAll static void init(){assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("third-party/disco/disco-3.0.0-all.jar")) && java.nio.file.Files.isRegularFile(java.nio.file.Path.of("data/disco/fr-general-20151126-lm-sim/disco.config")), "DISCO resources not installed");provider=(DiscoGroupSynonymProvider)SynonymProviderFactory.create(Path.of("src/test/resources/profiles/disco.yml"));analyzer=new FrenchNlpAnalyzer();config=TransformationConfigLoader.load(Path.of("src/test/resources/profiles/disco.yml"));}
 String run(String s){return new SentenceTransformerService(analyzer,provider).transformText(s,config).transformed();}
 @Test void nativeModelAndCoreNlpCoexistUnderJava17(){assertEquals(17,Runtime.version().feature());ClassLoader old=Thread.currentThread().getContextClassLoader();assertFalse(run("Une automobile traverse la place devant le garage.").isBlank());assertSame(old,Thread.currentThread().getContextClassLoader());}
 @Test void appropriateAdjectiveIsChosenWithMissingOmwSourceSense()throws Exception{String s=Files.readString(Path.of("evaluation/user-text/original.txt")).stripTrailing();assertTrue(run(s).contains("souvenir mémorable"));}
 @Test void rawRobertConfusionsAreNotReintroduced()throws Exception{String r=run(Files.readString(Path.of("evaluation/user-text/original.txt")).stripTrailing());assertFalse(r.contains("lignage"));assertFalse(r.contains("amidonné"));assertFalse(r.contains("trois amis"));assertTrue(r.contains("état d'esprit"));}
 @Test void groupAnchorCannotAuthorizeAnUnprovenCandidate(){assertEquals("La police interroge les témoins présents dans la rue.",run("La police interroge les témoins présents dans la rue."));}
 @Test void precisionDoesNotAutomaticallyBecomeStrictness(){assertEquals("Le chef demande une interprétation plus précise du passage.",run("Le chef demande une interprétation plus précise du passage."));}
 @Test void sourceWordspaceUnavailableDoesNotAuthorizeAnything(){var a=analyzer.analyzeFirstSentence("Le zzmotinconnu avance.");var t=a.tokens().stream().filter(x->x.word().equals("zzmotinconnu")).findFirst().orElseThrow();assertTrue(provider.candidates(t,SynonymRegister.COURANT,a).isEmpty());}
 @Test void properNamesAreProtected(){var a=analyzer.analyzeFirstSentence("Marie observe le paysage.");var t=a.tokens().get(0);assertTrue(provider.inventory(t,SynonymRegister.COURANT,a).isEmpty());}
 @Test void scoreIsBoundedAndPerCandidate(){var a=analyzer.analyzeFirstSentence("Une automobile traverse la place devant le garage.");var t=a.tokens().stream().filter(x->x.word().equals("automobile")).findFirst().orElseThrow();for(var d:provider.details(t,a)){assertTrue(d.score()>=0&&d.score()<=1);assertEquals(Math.cbrt(d.groupFit()*d.lexicalFit()*d.contextFit()),d.score(),1e-12);}}
 @Test void onlyRobertInventoryCanBeSelected(){var a=analyzer.analyzeFirstSentence("Une automobile traverse la place devant le garage.");var t=a.tokens().stream().filter(x->x.word().equals("automobile")).findFirst().orElseThrow();assertTrue(provider.inventory(t,SynonymRegister.COURANT,a).containsAll(provider.candidates(t,SynonymRegister.COURANT,a)));assertFalse(provider.assess(t,SynonymRegister.COURANT,"avion",a).allowed());}
 @Test void repeatedTransformationIsDeterministic(){String s="Une automobile traverse la place devant le garage.";assertEquals(run(s),run(s));}
}
