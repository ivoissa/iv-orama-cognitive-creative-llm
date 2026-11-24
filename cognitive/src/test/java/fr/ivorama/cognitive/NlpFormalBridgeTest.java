package fr.ivorama.cognitive;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NlpFormalBridgeTest {
 private TextReadingRegistry.Registered read(String source){var r=new TextReadingRegistry();return r.read(r.analyze(source).json());}
 private void admitted(String source){
  var r=read(source);assertTrue(r.complete(),r.json());
  assertTrue(r.readings().stream().anyMatch(x->x.reader().equals("NLP_VALIDATED_DISTRIBUTIVE")));
  for(var p:r.premises()){
   var e=p.evidence();String[] span=e.source().split(":");
   assertEquals(source.substring(Integer.parseInt(span[1]),Integer.parseInt(span[2])),e.excerpt());
  }
 }
 @Test void lexicalAndIdentifierVariations(){
  for(String noun:List.of("capteur","module","relais","robot","satellite"))
   for(String adjective:List.of("stables","chauds","froids","grands"))
    admitted("Le "+noun+" z71 et le "+noun+" z93 sont chacun "+adjective+".");
 }
 @Test void agreementAndMultipleSubjects(){
  admitted("La lampe l7 et la porte p8 sont chacune ouvertes.");
  admitted("La lampe l7 et le module m8 sont chacun froids.");
  admitted("Le robot r7 et le satellite s8 et le capteur c9 sont chacun stables.");
 }
 @Test void retainsOriginalSpansAfterContextAndWhitespace(){
  admitted("Le module m8 est disponible.\n  La lampe l7  et la porte p8 sont chacune ouvertes.");
 }
 @Test void rejectsScopeAmbiguityAndUncoveredWords(){
  for(String text:List.of(
   "Le module m1 et le relais r2 sont stables.",
   "Le module m1 et le relais r2 sont chacun probablement stables.",
   "Le module m1 et le relais r2 sont chacun stables demain.",
   "Le module m1 et le relais r2 ne sont pas chacun stables.",
   "Si le module m1 et le relais r2 sont chacun stables, alors le module m1 est actif.",
   "Il et le relais r2 sont chacun stables.",
   "Le module m1 et le relais r2 sont chacun stables ?",
   "Le module m1 et le relais r2 sont chacun stables !",
   "« Le module m1 et le relais r2 sont chacun stables. »",
   "Le module m1 et le relais r2 sont chacun ultramagnétiques.",
   "La lampe l7 et la porte p8 sont chacun ouvertes.",
   "Le module m1 et le relais r2 sont chacun stable.",
   "Le module m1 et le relais r2 sont chacune stables.",
   "Le module et le relais sont chacun stables."))assertFalse(read(text).complete(),text);
 }
 @Test void jsonTamperingCannotPromoteCandidate(){
  var r=new TextReadingRegistry();var text="Le robot r7 et le satellite s8 sont chacun stables.";
  var original=r.analyze(text).json();assertTrue(r.read(original).complete());
  assertThrows(IllegalArgumentException.class,()->r.read(original.replace("NAMED_DISTRIBUTIVE_COORDINATION","INVENTED_RULE")));
  assertThrows(IllegalArgumentException.class,()->r.read(original.replace("\"accepted\":true","\"accepted\":false")));
 }
 @Test void reasoningUsesMappedAssertionsAndAbstainsOnPartialText(){
  String source="Le module m1 et le relais r2 sont chacun stables. Si le module m1 est stable, alors le module m1 est actif.";
  var a=new TextContextAnalysis(CreativeTextEngine.defaults());var result=a.analyze(source);
  assertTrue(result.formallyComplete());assertFalse(result.inferences().isEmpty());
  assertTrue(result.inferences().stream().allMatch(TextContextAnalysis.Inference::proofChecked));
  assertTrue(result.registry().orElseThrow().premises().getFirst().evidence().excerpt().contains("chacun"));
  var partial=a.analyze(source+" Un passage hors grammaire.");
  assertFalse(partial.formallyComplete());assertTrue(partial.inferences().isEmpty());
 }
 @Test void laterObservationsUseRegisteredEntityIdentity(){
  var a=new TextContextAnalysis(CreativeTextEngine.defaults());
  var r=a.analyze("Le relais r2 et le module m1 sont chacun stables. Le module m1 est actif.");
  assertTrue(r.formallyComplete());
  var assertion=r.observations().stream().filter(o->o.excerpt().equals("Le module m1 est actif")).findFirst().orElseThrow();
  assertTrue(assertion.explanation().contains("module m1"),assertion.explanation());
  assertFalse(assertion.explanation().contains("relais r2"),assertion.explanation());
 }
 @Test void preservesStrictReaderAndDoesNotOpenQuestions(){
  var registry=new TextReadingRegistry();
  var strict="Le module m1 est stable. Si le module m1 est stable, alors le module m1 est actif.";
  assertTrue(registry.read(registry.analyze(strict).json()).complete());
  var cycle=new IntegratedQuestionCycle();
  var router=new FrenchReasoningRouter(cycle,q->List.of());
  router.respond("Analyse ce texte : « Le module m1 et le relais r2 sont chacun stables. »");
  assertEquals(0,cycle.questionActCount());assertTrue(cycle.premises().isEmpty());
 }
}
