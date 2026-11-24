package fr.ivorama.cognitive.generative;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.ivorama.cognitive.generative.WikipediaBridgeTest.result;
class AutomaticFormal0647Test {
 static final String TARGET="Le relais r8 n'est pas alimenté. Si le relais r8 est actif, alors le relais r8 est alimenté. Est-ce que le relais r8 est actif ?";
 static WikipediaResearch offline(){return new WikipediaResearch((m,p)->{throw new AssertionError("No query planner");},q->{throw new AssertionError("No network");},false);}
 static GroundedConversation noLlm(){return new GroundedConversation(new LocalConversation((m,p)->{throw new AssertionError("No LLM");},8),offline(),new RequestPlanner((m,p)->{throw new AssertionError("No request planner");}));}
 @Test void failedComparisonNowRoutesWithoutCommandOrLlm(){
  var turn=noLlm().ask(TARGET,t->{});
  assertEquals("FALSE_CONDITIONALLY",turn.formal().orElseThrow().status());assertTrue(turn.formal().get().proofChecked());
  assertEquals("AUTOMATIC_COPULAR",turn.formal().get().route());assertEquals("IVORAMA_SYMBOLIC",turn.generation().model());assertTrue(turn.generation().text().startsWith("Non,"));
 }
 @Test void modusTollensGeneralizesAcrossUnseenNamesAndProperties(){
  for(String[] words:List.of(new String[]{"dispositif","z9","lumineux","amorcé"},new String[]{"instrument","k4","sensible","activé"},new String[]{"mécanisme","v6","mobile","assemblé"})){
   String noun="le "+words[0]+" "+words[1];String text=noun+" n'est pas "+words[3]+". Si "+noun+" est "+words[2]+", alors "+noun+" est "+words[3]+". Est-ce que "+noun+" est "+words[2]+" ?";
   var turn=noLlm().ask(text,t->{});assertEquals("FALSE_CONDITIONALLY",turn.formal().orElseThrow().status());assertTrue(turn.formal().get().proofChecked());
  }
 }
 @Test void forwardChainUsesSameCore(){
  var turn=noLlm().ask("Le module m4 est prêt. Si le module m4 est prêt, alors le module m4 est actif. Si le module m4 est actif, alors le module m4 est stable. Est-ce que le module m4 est stable ?",t->{});
  assertEquals("TRUE_CONDITIONALLY",turn.formal().orElseThrow().status());assertTrue(turn.formal().get().proofChecked());
 }
 @Test void absentPremiseRemainsUnknownRatherThanFalse(){
  var turn=noLlm().ask("Le composant c8 est stable. Est-ce que le composant c8 est actif ?",t->{});
  assertEquals("UNDETERMINED",turn.formal().orElseThrow().status());assertFalse(turn.formal().get().proofChecked());assertTrue(turn.generation().text().startsWith("Les prémisses"));
 }
 @Test void inconsistencyDoesNotExplode(){
  var turn=noLlm().ask("Le module b7 est actif. Le module b7 n'est pas actif. Est-ce que le module b7 est stable ?",t->{});
  assertEquals("INCONSISTENT",turn.formal().orElseThrow().status());assertFalse(turn.formal().get().proofChecked());
 }
 @Test void partialReadingFallsBackWithoutCertifying(){
  for(String input:List.of("Le relais r8 n'est pas alimenté. Un technicien arrive demain. Est-ce que le relais r8 est actif ?",TARGET+" Explique aussi les causes possibles.","Marie remet une clé à Paul. Qui détient la clé ?")){
   assertTrue(AutomaticFormalRouter.recognize(input).isEmpty(),input);
   var calls=new AtomicInteger();var turn=new GroundedConversation(new LocalConversation((m,p)->{calls.incrementAndGet();return result("Réponse générée non certifiée.","STOP");},8),offline()).ask(input,t->{});
   assertEquals(1,calls.get());assertTrue(turn.formal().isEmpty());
  }
 }
 @Test void standaloneKnowledgeQuestionsAndDirectivesKeepTheirRoutes(){
  for(String input:List.of("Le canard est-il un oiseau ?","Écris trois phrases sur une rivière.","Analyse ce texte : « Le relais r8 est actif. Est-ce que le relais r8 est actif ? »",":analyser Un récit.","{\"sourceText\":\"test\"}","Le relais r8 est actif. Est-ce que le relais r8 est forcément stable ?","Le relais r8 est actif. Wikipédia est-elle une source ?"))assertTrue(AutomaticFormalRouter.recognize(input).isEmpty(),input);
 }
 @Test void acceptedSpansCoverOriginalIncludingAccentsAndWhitespace(){
  String input="  Le détecteur d3 est éclairé.\nSi le détecteur d3 est éclairé, alors le détecteur d3 est prêt.\nEst-ce que le détecteur d3 est prêt ?  ";
  var translated=AutomaticFormalRouter.recognize(input).orElseThrow();assertEquals(input,translated.problem().orElseThrow().sourceText());assertTrue(translated.segments().stream().allMatch(s->s.source().equals(input.substring(s.start(),s.end()))));
 }
 @Test void conditionalTurnIsRememberedButNeverAddedAsWorldFact(){
  var memory=new LocalConversation((m,p)->{throw new AssertionError();},8);var session=new GroundedConversation(memory,offline());
  session.ask(TARGET,t->{});assertEquals(3,memory.history().size());assertTrue(memory.history().getLast().toString().contains("aucune vérité empirique certifiée"));session.reset();assertEquals(1,memory.history().size());
 }
 @Test void callbackFailureDoesNotCommitAutomaticTurn(){
  var memory=new LocalConversation((m,p)->{throw new AssertionError();},8);var session=new GroundedConversation(memory,offline());
  assertThrows(IllegalStateException.class,()->session.ask(TARGET,t->{throw new IllegalStateException("callback");}));assertEquals(1,memory.history().size());
 }
 @Test void explicitCommandStillUsesExplicitReceipt(){
  var t=noLlm().ask(":raisonner "+TARGET,x->{});assertEquals("EXPLICIT",t.formal().orElseThrow().route());assertTrue(t.formal().get().proofChecked());
 }
 @Test void ablationDisablesOnlyAutomaticRouting(){
  var calls=new AtomicInteger();var memory=new LocalConversation((m,p)->{calls.incrementAndGet();return result("Réponse du modèle.","STOP");},8);
  var session=new GroundedConversation(memory,offline(),null,false);
  assertTrue(session.ask(TARGET,t->{}).formal().isEmpty());assertEquals(1,calls.get());
  assertEquals("EXPLICIT",session.ask(":raisonner "+TARGET,t->{}).formal().orElseThrow().route());assertEquals(1,calls.get());
 }
}
