package com.ivorama.nlp.transform;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
public final class HarmonizedYamlSmokeMain {
  private static int n;
  private static void check(boolean b) { if(!b) throw new AssertionError("check "+n); n++; }
  private static TransformationConfig parse(String yaml) { return TransformationConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))); }
  private static void reject(String yaml) { try { parse(yaml); throw new AssertionError("YAML accepted: "+yaml); } catch(IllegalArgumentException expected) { n++; } }
  public static void main(String[] args) {
    var c=TransformationConfigLoader.loadDefault();
    check(c.replaceNounsEnabled());
    check(c.yodaMode() && c.emphaseColores() && c.joyeuseCirconstanceMode());
    check(TransformationConfigLoader.loadLexicalDefault().wolfEnabled());
    check(!TransformationConfigLoader.loadLexicalDefault().libraryEnabled());
    var noNouns=parse("transformations:\n  replace_first_and_third_nouns_enabled: false\n");
    check(!noNouns.replaceNounsEnabled());
    var noSyn=parse("synonyms:\n  enabled: false\n");
    check(!noSyn.replaceNounsEnabled() && !noSyn.emphaseColores());
    reject("transformations:\n  yoda_mode_enabled: true\n  yoda_mode_enabled: false\n");
    reject("transformations:\n  yoda_mode_enabled: yes\n  bogus_enabled: true\n");
    reject("transformations:\n   yoda_mode_enabled: true\n");
    reject("transformations:\n  yoda_mode_enabled: 100\n");
    var stoic=parse("transformations:\n  stoic_mode_enabled: true\n");
    check(stoic.stoiquePhiloMode());
    check(!c.stoiquePhiloMode());
    var disabled=TransformationConfigLoader.loadLexicalDefault();
    check(disabled.enabled());
    System.out.println("HARMONIZED YAML: "+n+"/"+n+" OK");
  }
}
