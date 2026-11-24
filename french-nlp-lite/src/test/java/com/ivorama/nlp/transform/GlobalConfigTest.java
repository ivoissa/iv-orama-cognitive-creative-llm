package com.ivorama.nlp.transform;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class GlobalConfigTest {
 @Test void readsBenchmarkSwitchAndMultilineDefaultText(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
  Path yaml = directory.resolve("application.yml");
  Files.writeString(yaml, """
      benchmark_enabled: false
      default_sentence: |-
        Première ligne : # conservé.

        Deuxième ligne.
      transformations:
        yoda_mode_enabled: true
      """);
  var settings = TransformationConfigLoader.loadGlobal(yaml);
  assertFalse(settings.benchmarkEnabled());
  assertEquals("Première ligne : # conservé.\n\nDeuxième ligne.", settings.defaultSentence());
  assertTrue(settings.transformations().yodaMode());
  Files.writeString(yaml, "benchmark_enabled: true\ndefault_sentence: \"Un ciel bleu.\"\n");
  settings = TransformationConfigLoader.loadGlobal(yaml, false);
  assertTrue(settings.benchmarkEnabled());
  assertEquals("Un ciel bleu.", settings.defaultSentence());
  Files.writeString(yaml, "transformations:\n  yoda_mode_enabled: true\n");
  assertTrue(TransformationConfigLoader.loadGlobal(yaml).benchmarkEnabled());
  assertFalse(TransformationConfigLoader.loadGlobal(yaml, false).benchmarkEnabled());
 }

 @Test void productionConfigIsUniqueAndComplete() throws Exception {
  try(var files=Files.list(TransformationConfigLoader.defaultPath().getParent())) { assertEquals(1,files.count()); }
  var g=TransformationConfigLoader.loadGlobal(TransformationConfigLoader.defaultPath());
  assertTrue(g.transformations().emphaseMode()); assertTrue(g.transformations().yodaMode());
  assertFalse(g.transformations().stoiquePhiloMode()); assertFalse(g.lexical().disco().enabled());
  assertEquals(100, g.transformations().adjectiveSubstitutionRate());
  assertEquals(100, g.transformations().nounSubstitutionRate());
 }
}
