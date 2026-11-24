package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import org.junit.jupiter.api.*;import java.util.*;import java.nio.file.*;import static org.junit.jupiter.api.Assertions.*;
class RobertPipelineTest {
 @Test void realRobertFactoryIsConnectedAndScoreBounded()throws Exception{var p=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/candidate.yml"));var a=new FrenchNlpAnalyzer().analyzeFirstSentence("La bicyclette possède deux roues et des pédales.");var t=a.tokens().stream().filter(x->x.word().equals("bicyclette")).findFirst().orElseThrow();assertTrue(p.inventory(t,SynonymRegister.COURANT,a).contains("vélo"));var d=p.assess(t,SynonymRegister.COURANT,"vélo",a);assertTrue(d.allowed(),d.toString());assertTrue(d.evidence().contains("groups="));assertTrue(d.score()>=0&&d.score()<=1);assertEquals("Le vélo possède deux roues et des pédales.",new SentenceTransformerEngine(p).transform(a,TransformationConfigLoader.load(Path.of("src/test/resources/profiles/candidate.yml"))).transformed());}
 @Test void derivationAddsContextWithoutProvingSynonymy() throws Exception {
  var evidence = RobertGroupEvidence.load();
  var senses = evidence.sourceSenses("enseignement", "n");
  assertTrue(senses.stream().anyMatch(sense -> evidence.derivationalSupport(sense, "enseigner") > 0
      && evidence.neighborhood(sense, "enseigner") == 0));
  var selector = new RobertDisambiguator(evidence, RobertCandidateIndex.load());
  assertFalse(selector.assess("enseignement", "n", "enseigner", List.of("transmettre", "savoir")).allowed());
 }
 @Test void dependentTermHasTripleWeightWithoutPollutingUnweightedCache() throws Exception {
  var selector = new RobertDisambiguator(RobertGroupEvidence.load(), RobertCandidateIndex.load());
  var terms = List.of("enseigner", "zzinconnuq");
  var global = selector.scores("enseignement", "n", terms);
  var weighted = selector.scores("enseignement", "n", terms, List.of("enseigner"));
  assertTrue(global.get(0).total() > 0);
  assertEquals(1.5 * global.get(0).total(), weighted.get(0).total(), 1e-12);
  assertEquals(global, selector.scores("enseignement", "n", terms));
 }
 @Test void measuredTemperatureKeepsItsPhysicalSense() throws Exception {
  Path yaml = TransformationConfigLoader.defaultPath();
  var provider = SynonymProviderFactory.create(yaml);
  var analysis = new FrenchNlpAnalyzer().analyzeFirstSentence("La température atteignit trente degrés.");
  var subject = analysis.tokens().stream().filter(t -> t.word().equals("température")).findFirst().orElseThrow();
  assertTrue(provider.inventory(subject, SynonymRegister.COURANT, analysis).contains("fièvre"));
  assertFalse(provider.assess(subject, SynonymRegister.COURANT, "fièvre", analysis).allowed());
 }
 @Test void semanticNeighborsNeverBecomeCandidates()throws Exception{var idx=RobertCandidateIndex.load();assertFalse(idx.lookup("père").stream().anyMatch(m->m.candidate().equals("fils")));var e=RobertGroupEvidence.load();var selector=new RobertDisambiguator(e,idx);assertFalse(selector.assess("père","n","fils",List.of("enfant","famille","parent")).allowed());}
 @Test void unknownContextCannotAuthorizeSubstitution()throws Exception{var d=new RobertDisambiguator(RobertGroupEvidence.load(),RobertCandidateIndex.load());assertFalse(d.assess("lettre","n","graphème",List.of("zzinconnuq")).allowed());}
 @Test void reviewedEvidencePreventsSpuriousGameSense()throws Exception{var d=new RobertDisambiguator(RobertGroupEvidence.load(),RobertCandidateIndex.load());var r=d.select("médecin","n",List.of("patient","examiner","cabinet"));assertNotEquals("00432356-n",r.sense());assertEquals("10020890-n",r.sense());assertEquals("CANDIDATES",r.status());assertTrue(r.candidates().contains("docteur"));assertFalse(RobertGroupEvidence.load().reviewed("médecin","00432356-n"));}
 @Test void scoreRejectsNonFiniteOrOutOfRange(){assertThrows(IllegalArgumentException.class,()->new CandidateDecision(true,"x",Double.NaN,"",""));assertThrows(IllegalArgumentException.class,()->new CandidateDecision(true,"x",1.1,"",""));}
 @Test void weightsAreFiniteAndNormalizedByConstruction(){assertThrows(IllegalArgumentException.class,()->new RobertSettings(true,0,0,0,0));assertThrows(IllegalArgumentException.class,()->new RobertSettings(true,Double.NaN,1,0,0));assertThrows(IllegalArgumentException.class,()->new RobertSettings(true,Double.MAX_VALUE,Double.MAX_VALUE,0,0));assertThrows(IllegalArgumentException.class,()->new DecisionWeights(Double.MAX_VALUE,Double.MAX_VALUE));}
 @Test void actualTypedAntonymAndPartRelationsExist(){var e=RobertGroupEvidence.load();assertTrue(e.senses("chaud","a").stream().anyMatch(s->e.forbiddenRelation(s,"froid","a").contains("antonym")));assertTrue(e.senses("fenêtre","n").stream().anyMatch(s->e.forbiddenRelation(s,"vitre","n").contains("holo")));}
 @Test void yamlWeightsActuallyReachFactory()throws Exception{var a=new FrenchNlpAnalyzer().analyzeFirstSentence("La bicyclette possède deux roues et des pédales.");var t=a.tokens().stream().filter(x->x.word().equals("bicyclette")).findFirst().orElseThrow();var full=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/candidate.yml")).assess(t,SynonymRegister.COURANT,"vélo",a);var direct=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/ablation-context.yml")).assess(t,SynonymRegister.COURANT,"vélo",a);assertNotEquals(full.allowed(),direct.allowed());}
}
