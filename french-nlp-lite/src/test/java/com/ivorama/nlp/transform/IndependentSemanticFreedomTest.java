package com.ivorama.nlp.transform;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class IndependentSemanticFreedomTest {
 @TempDir Path dir;
 Path yaml(String body) throws Exception {return Files.writeString(dir.resolve("rules.yml"),body);}
 @Test void independentThresholdsAndLegacyFallback() throws Exception {
  var s=TransformationConfigLoader.loadLexical(yaml("transformations:\n  adjective_semantic_freedom: 25\n  noun_semantic_freedom: 50\nsynonyms:\n  disco:\n    enabled: true\n    semantic_freedom: 75\n")).disco();
  assertEquals(25,s.semanticFreedom("a"));assertEquals(50,s.semanticFreedom("n"));assertEquals(75,s.semanticFreedom("v"));
  assertEquals(.65,s.effectiveRetention("a"),1e-12);assertEquals(.50,s.effectiveRetention("n"),1e-12);
  assertEquals(.015,s.effectiveMargin("a"),1e-12);assertEquals(.01,s.effectiveMargin("n"),1e-12);
 }
 @Test void zeroOverridesGlobalAndAbsentInherits() throws Exception {
  var s=TransformationConfigLoader.loadLexical(yaml("transformations:\n  adjective_semantic_freedom: 0\nsynonyms:\n  disco:\n    enabled: true\n    semantic_freedom: 75\n")).disco();
  assertEquals(0,s.semanticFreedom("a"));assertEquals(75,s.semanticFreedom("n"));
 }
 @Test void explicitZeroDisablesOnlyItsWordClass() {
  var settings = new DiscoSettings(true, Path.of("x"), .01, .02, .8, 0, 0, null);
  assertTrue(settings.editsDisabled("a"));
  assertFalse(settings.editsDisabled("n"));
  assertEquals(0, settings.semanticFreedom("n"));
 }
 @Test void rejectsInvalidValuesInBothLoaders() throws Exception {
  for(String key:new String[]{"adjective_semantic_freedom","noun_semantic_freedom"})for(String value:new String[]{"-1","101","25.5","abc"}){
   var p=yaml("transformations:\n  "+key+": "+value+"\nsynonyms:\n  disco:\n    enabled: true\n");
   assertThrows(IllegalArgumentException.class,()->TransformationConfigLoader.load(p));assertThrows(IllegalArgumentException.class,()->TransformationConfigLoader.loadLexical(p));
  }
 }
 @Test void disabledDiscoAllowsStoredSliders() throws Exception {var p=yaml("transformations:\n  noun_semantic_freedom: 50\n");assertFalse(TransformationConfigLoader.loadLexical(p).disco().enabled());assertNotNull(TransformationConfigLoader.load(p));}
 @Test void allModesProfileLoads(){var p=Path.of("src/test/resources/profiles/all-modes.yml");var c=TransformationConfigLoader.load(p);assertTrue(c.emphaseMode());assertTrue(c.yodaMode());assertTrue(c.joyeuseCirconstanceMode());assertTrue(c.respirationCoolMode());assertFalse(c.stoiquePhiloMode());assertEquals(25,TransformationConfigLoader.loadLexical(p).disco().semanticFreedom("a"));}
 @Test void fullRangeAndIsolation(){for(int n=0;n<=100;n++){var s=new DiscoSettings(true,Path.of("x"),.01,.02,.8,0,n,50);assertEquals(n,s.semanticFreedom("a"));assertEquals(.5,s.effectiveRetention("n"),1e-12);}}
}
