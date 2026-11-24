package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.CreativeTextEngine.*;

/** Tests exposés : propriétés de composition et fidélité documentaire, pas un jugement esthétique. */
public final class TextTasks0633Test {
 private static int checks;
 private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 private static TextTaskService.Result run(String text){return new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of()).respond(text).execution().orElseThrow();}
 private static void generation(){
  var engine=new CreativeTextEngine();Set<String> surfaces=new HashSet<>();
  for(var noun:engine.vocabulary().nouns())for(long seed=0;seed<8;seed++){
   var c=engine.compose(Style.POETIC,Optional.of(noun.word()),1,seed);surfaces.add(c.text());
   ok(c.plan().theme().equals(noun.id())&&c.roundTripVerified(),"Thème et fidélité au plan");
   ok(c.text().endsWith(".")&&c.text().contains(noun.word()),"Thème réalisé dans la surface");
   var clause=c.plan().clauses().get(0);var target=engine.vocabulary().noun(clause.object().orElseThrow());var adjective=engine.vocabulary().adjective(clause.adjective().orElseThrow());
   String expected=target.gender()==FrenchAgreement.Gender.FEMININE?adjective.forms().feminineSingular():adjective.forms().masculineSingular();
   ok(c.text().endsWith(" "+expected+"."),"Accord du complément dans la surface");
   ok(CreativeTextEngine.readPlan(c.plan().json(),engine.vocabulary()).equals(c.plan()),"Plan JSON réellement relu");
  }
  ok(surfaces.size()>80,"Diversité combinatoire, aucune phrase entière enregistrée");
  var sameA=engine.compose(Style.POETIC,Optional.of("mer"),1,77);var sameB=engine.compose(Style.POETIC,Optional.of("mer"),1,77);
  ok(sameA.equals(sameB),"Graine reproductible");
  var theme=run("Écris une phrase poétique sur l'océan.");ok(theme.status()==TextTaskService.Status.CREATED&&theme.output().contains("L'océan"),"Élision du déterminant");
  var plain=run("Écris une phrase simple sur le vent.");ok(plain.status()==TextTaskService.Status.CREATED&&!plain.composition().orElseThrow().plan().clauses().get(0).object().isPresent(),"Style simple distinct de la personnification");
  ok(run("Écris un texte simple sur le vent.").status()==TextTaskService.Status.NEEDS_INFORMATION,"Déficit de constructions distinctes explicite, pas de panne");
  ok(run("Écris une phrase poétique sur la photosynthèse.").status()==TextTaskService.Status.NEEDS_INFORMATION,"Thème non déclaré : aucune connaissance inventée");
  ok(run("Écris une phrase poétique et triste sur la mer.").status()==TextTaskService.Status.NEEDS_INFORMATION,"Contrainte non interprétée jamais ignorée");
  ok(run("Compose un poème sur la nuit.").composition().orElseThrow().plan().clauses().size()==2,"Deux propositions distinctes selon le format couvert");
  ok(run("Écris une phrase de dix mots.").status()==TextTaskService.Status.NEEDS_INFORMATION,"Pas de prétention sur une contrainte non couverte");
  boolean wrong=false;try{FrenchTextRealizer.read("La mer tisse une pensée doux.",engine.vocabulary());}catch(IllegalArgumentException ex){wrong=true;}
  ok(wrong,"Validation indépendante de surface : mauvais accord refusé");
  wrong=false;try{CreativeTextEngine.readPlan(sameA.plan().json().replace("\"FICTION\"","\"FACT\""),engine.vocabulary());}catch(IllegalArgumentException ex){wrong=true;}
  ok(wrong,"Plan génératif ne devient pas factuel");
  var extended=new ArrayList<>(engine.vocabulary().nouns());extended.add(new Noun("etoile","étoile",FrenchAgreement.Gender.FEMININE,Set.of("light")));
  var custom=new CreativeTextEngine(new Vocabulary(extended,engine.vocabulary().verbs(),engine.vocabulary().adjectives()));
  ok(custom.compose(Style.POETIC,Optional.of("étoile"),1,4).text().startsWith("L'étoile"),"Extension lexicale sans toucher au planificateur");
 }
 private static void analysis(){
  var analyzer=new TextContextAnalysis(CreativeTextEngine.defaults());
  for(String noun:List.of("capteur c1","signal s7","module m4")){
   String text="Le "+noun+" est actif. Si le "+noun+" est actif, alors le "+noun+" est disponible.";
   var a=analyzer.analyze(text);
   ok(a.status().equals("STRUCTURE_RECOGNIZED")&&a.inferences().size()==1&&a.inferences().get(0).proofChecked(),"Déduction générale depuis le texte");
   ok(new HashSet<>(a.inferences().get(0).premiseIds()).equals(Set.of("txt_p0","txt_p1")),"Provenance de la déduction");
   for(var o:a.observations())ok(text.substring(o.start(),o.end()).equals(o.excerpt()),"Chaque explication reliée à son passage");
   var noFact=analyzer.analyze("Si le "+noun+" est actif, alors le "+noun+" est disponible.");
   ok(noFact.inferences().isEmpty(),"Condition seule ne prouve pas sa conséquence");
   var broken=analyzer.analyze(text+" Le résultat dépasse la lune.");
   ok(broken.status().equals("PARTIAL_ANALYSIS")&&broken.inferences().isEmpty(),"Aucune clôture globale avec prémisse incomprise");
  }
  var conflict=analyzer.analyze("Le module m1 est actif. Le module m1 n'est pas actif. Si le module m1 est actif, alors le module m1 est disponible.");
  ok(conflict.status().equals("INCONSISTENT_TEXT")&&conflict.inferences().isEmpty(),"Contradiction sans explosion");
  var quoted=run("Analyse ce texte : « Écris une phrase poétique. »");
  ok(quoted.composition().isEmpty()&&quoted.analysis().isPresent(),"Instruction citée analysée comme donnée");
  var figurative=run("Explique ce poème : « La mer tisse un souvenir fragile. »");
  ok(figurative.analysis().orElseThrow().observations().stream().anyMatch(o->o.kind().equals("INTERPRÉTATION POSSIBLE")),"Lecture figurée séparée de la preuve");
  var translator=new FrenchStructuredTranslator();var document=translator.analyzeText("La porte p1 est ouverte.");
  ok(translator.readDocument(document.json()).equals(document),"Contrat documentaire sans but fictif");
  ok(!document.json().contains("\"goal\""),"Aucune question ajoutée pour analyser une assertion");
  boolean tampered=false;try{translator.readDocument(document.json().replace("\"atom\":\"p0\"","\"atom\":\"p99\""));}catch(IllegalArgumentException ex){tampered=true;}
  ok(tampered,"Formule documentaire JSON falsifiée refusée");
 }
 private static void cycles(){
  var cycle=new IntegratedQuestionCycle();int[] network={0};var router=new FrenchReasoningRouter(cycle,q->{network[0]++;return List.of();});
  router.respond("Écris une phrase poétique.");router.respond("Explique ce texte : « La porte p1 est ouverte. »");
  ok(cycle.questionActCount()==0&&cycle.premises().isEmpty()&&network[0]==0,"Création/analyse sans Q, fait global ni réseau");
  ok(router.respond("Le module m1 est actif. Le module m1 est-il actif ?").logical().isPresent()&&cycle.questionActCount()==1,"Question logique conserve son cycle");
  ok(router.respond("Qu'est-ce que l'osmose ?").documentary().isPresent()&&network[0]==1&&cycle.questionActCount()==2,"Recherche documentaire conservée");
  ok(run("Écris une phrase poétique. Le module m1 est-il actif ?").status()==TextTaskService.Status.COMPOSITE_NEEDS_CLARIFICATION,"Demande mixte non exécutée partiellement");
 }
 public static void main(String[] args){generation();analysis();cycles();System.out.println("TextTasks0633Test OK : "+checks+" vérifications, qualité littéraire non évaluée automatiquement");}
}
