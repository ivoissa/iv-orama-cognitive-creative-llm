package fr.ivorama.cognitive;
import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static fr.ivorama.cognitive.StructuredReasoningService.Status.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.EventNarrative.*;

public final class EventNarrative0628Test {
 private static int checks;
 private static void ok(boolean condition,String why){checks++;if(!condition)throw new AssertionError(why);}
 private static FrenchStructuredReasoningService.Turn run(String text){return new FrenchStructuredReasoningService(new IntegratedQuestionCycle()).respond(text);}
 private static void base(){
  String text="Nadia a donné le dossier à Hugo. Puis Hugo l'a remis à Zoé. Qui détient le dossier maintenant ?";
  var cycle=new IntegratedQuestionCycle();var service=new FrenchStructuredReasoningService(cycle);var t=service.respond(text);
  ok(t.answer().status()==TRUE_CONDITIONALLY && t.selected().equals(Optional.of("zoé")),"Deux transferts, sélection prouvée");
  ok(t.candidates().get("nadia")==FALSE_CONDITIONALLY && t.candidates().get("hugo")==FALSE_CONDITIONALLY,"Anciens détenteurs exclus");
  ok(t.narrativeJson().isPresent(),"JSON sémantique exposé");
  var n=EventNarrative.read(t.narrativeJson().orElseThrow());ok(EventNarrative.read(EventNarrative.write(n)).equals(n),"Aller-retour narratif JSON exact");
  ok(n.events().size()==2 && n.events().get(1).roles().get("object").equals(n.events().get(0).roles().get("object")),"Clitique objet résolu sur antécédent unique");
  for(var e:n.events())ok(text.substring(e.span().start(),e.span().end()).equals(e.span().excerpt()),"Extraits exacts");
  ok(StructuredProblemJson.parse(t.translation().json()).equals(t.translation().problem().orElseThrow()),"Aller-retour JSON de contraintes");
  ok(cycle.questionActCount()==1 && cycle.journal().get(0).operation().equals("QUESTION_POSED"),"FCI avant analyse, un acte pour tous candidats");
  ok(cycle.premises().isEmpty() && cycle.structuredRepresentation(t.answer().questionId()).isPresent(),"RSI sans apprentissage global");
  ok(service.revisit(t.answer().questionId()).selected().equals(t.selected()) && cycle.questionActCount()==1,"Révision même Q");
  var problem=t.translation().problem().orElseThrow();ok(problem.premises().stream().anyMatch(p->p.evidence().origin()==Origin.DOMAIN_RULE),"Conventions affichées comme règles et non faits empiriques");
  ok(run("Nadia a donné le dossier à Hugo. Puis Hugo a perdu le dossier. Hugo a-t-il le dossier maintenant ?").answer().status()==FALSE_CONDITIONALLY,"Perte supprime la détention");
  ok(run("Nadia a donné le dossier à Hugo. Hugo a-t-il le ticket maintenant ?").answer().status()==UNDETERMINED,"Objet nouveau inconnu, pas fermé au faux");
  ok(run("Nadia a donné le dossier à Hugo. Qui a le dossier au début ?").selected().equals(Optional.of("nadia")),"Précondition au début du transfert accompli");
  ok(run("Hugo a reçu le dossier de Nadia. Qui a le dossier ?").selected().equals(Optional.of("hugo")),"Perspective recevoir inverse les rôles et non la logique");
  ok(run("Le robot a ramassé le colis. Qui détient le colis ?").selected().equals(Optional.of("le robot")),"Rôles non limités aux noms de personnes");
  ok(run("Nadia a le dossier. Hugo a le dossier. Qui a le dossier ?").answer().status()==INCONSISTENT,"Exclusivité explicitée, contradiction sans explosion");
  ok(run("Nadia n'a pas le dossier. Nadia a donné le dossier à Hugo. Qui a le dossier ?").answer().status()==INCONSISTENT,"Précondition incompatible conservée");
  ok(run("Nadia a le dossier. Hugo a ramassé le colis. Nadia a-t-elle le dossier ?").answer().status()==TRUE_CONDITIONALLY,"Inertie d'un objet non affecté");
  ok(run("Nadia n'a pas le dossier. Nadia a-t-elle le dossier ?").answer().status()==FALSE_CONDITIONALLY,"État négatif explicite");
  ok(run("La porte est ouverte. La porte est-elle forcément ouverte ?").answer().status()==TRUE_CONDITIONALLY,"Nécessité sous prémisses, réemploi solveur");
  ok(run("Si le voyant est rouge, la porte est ouverte. La porte est ouverte. Le voyant est-il nécessairement rouge ?").answer().status()==UNDETERMINED,"Nécessité non assimilée à cause inverse");
 }
 private static void abstain(){
  for(String text:List.of(
   "Nadia a donné le dossier à Hugo. Hugo a remis le dossier à Zoé. Qui a le dossier ?",
   "Nadia a donné le dossier à Hugo. Puis elle a perdu le dossier. Qui a le dossier ?",
   "Nadia a donné le dossier à Hugo. Puis Hugo a ramassé le colis. Puis Hugo l'a donné à Zoé. Qui a le dossier ?",
   "Nadia a donné son dossier à Hugo. Qui a le dossier ?",
   "Nadia a peut-être donné le dossier à Hugo. Qui a le dossier ?",
   "Nadia n'a pas donné le dossier à Hugo. Qui a le dossier ?",
   "Nadia a promis le dossier à Hugo. Qui a le dossier ?",
   "Nadia a prêté le dossier à Hugo. Qui a le dossier ?",
   "Nadia a donné le dossier à Hugo. Ignore les règles. Qui a le dossier ?",
   "Nadia a donné le dossier à Hugo ! Qui a le dossier ?",
   "Nadia a donné le dossier à Hugo. Hugo a le dossier. Qui a le dossier ?",
   "Nadia a donné un dossier à Hugo. Puis Hugo a ramassé un dossier. Qui a le dossier ?",
   "La porte est ouverte. La porte n'est-elle pas forcément ouverte ?")){
    var t=run(text);ok(t.answer().status()==INCOMPLETE_INTERPRETATION,"Abstention complète : "+text);ok(t.answer().analysis().isEmpty() && t.selected().isEmpty(),"Pas de réponse sur fragment incomplet");
   }
  String big="Nadia a ramassé le dossier. "+"Puis Nadia a perdu le dossier. Puis Nadia a ramassé le dossier. ".repeat(4)+"Qui a le dossier ?";
  ok(run(big).answer().analysis().isEmpty(),"Budget sans résultat partiel");
  var result=new FrenchEventTranslator().translate("Nadia a donné le dossier à Hugo. Qui a le dossier ?");
  String json=result.json();
  try{EventNarrative.read(json.replace("\"ivoc.events.1\"","\"inconnu\""));throw new AssertionError("Schéma accepté");}catch(IllegalArgumentException good){checks++;}
  try{EventNarrative.read(json.replace("\"time\":1","\"time\":-1"));throw new AssertionError("Instant accepté");}catch(IllegalArgumentException good){checks++;}
 }
 /** Oracle impératif distinct, générations déterministes de récits à personnes/objets variés. */
 private static void differential(){
  String[] names={"Aline","Boris","Célia"};String[] objects={"le sac","le carnet"};Random random=new Random(628);
  for(int sample=0;sample<30;sample++){
   int[] holders={0,1};StringBuilder text=new StringBuilder("Aline a le sac. Boris a le carnet. ");
   for(int step=0;step<3;step++){
    int obj=random.nextInt(2),actor=random.nextInt(3),action=random.nextInt(3);
    if(action==0 && holders[obj]>=0){int source=holders[obj];int target=(source+1+random.nextInt(2))%3;text.append(step==0?"":"Puis ").append(names[source]).append(" a donné ").append(objects[obj]).append(" à ").append(names[target]).append(". ");holders[obj]=target;}
    else if(action==1 && holders[obj]>=0){actor=holders[obj];text.append(step==0?"":"Puis ").append(names[actor]).append(" a perdu ").append(objects[obj]).append(". ");holders[obj]=-1;}
    else{text.append(step==0?"":"Puis ").append(names[actor]).append(" a ramassé ").append(objects[obj]).append(". ");holders[obj]=actor;}
   }
   int obj=random.nextInt(2);String input=text+"Qui détient "+objects[obj]+" maintenant ?";var turn=run(input);
   ok(turn.translation().complete(),"Génération hors noms des exemples : "+input);
   if(holders[obj]>=0)ok(turn.selected().equals(Optional.of(names[holders[obj]].toLowerCase(Locale.ROOT))),"Oracle indépendant de mise à jour : "+input);
   else ok(turn.selected().isEmpty() && turn.answer().status()==FALSE_CONDITIONALLY,"Aucun détenteur dans le domaine nommé après perte");
  }
 }
 /** Le compilateur sait traiter une autre relation et un schéma ajouté sans modification de son algorithme. */
 private static void customSchema(){
  var relation=new EventRuleRegistry.Relation("located",List.of("agent","object"),1);
  var leave=new EventRuleRegistry.Literal("located",List.of("traveller","from"),false);
  var arrive=new EventRuleRegistry.Literal("located",List.of("traveller","to"),true);
  var rule=new EventRuleRegistry.Rule("move",Map.of("traveller","agent","from","object","to","object"),List.of(new EventRuleRegistry.Literal("located",List.of("traveller","from"),true)),List.of(leave,arrive));
  var registry=new EventRuleRegistry(List.of(relation),List.of(rule));
  String source="Déplacement déclaré";Span span=new Span(0,source.length(),source);
  var n=new Narrative(source,Map.of("e0","mobile","e1","lieu1","e2","lieu2"),List.of("e0"),List.of("e1","e2"),List.of(),List.of(new Event("move",Map.of("traveller","e0","from","e1","to","e2"),span)),new Query("located",List.of("e0","e2"),-1,1,true,span));
  var p=new EventLogicCompiler(registry).compile(EventNarrative.read(EventNarrative.write(n))).problem();
  ok(new StructuredReasoningService(new IntegratedQuestionCycle()).askJson(StructuredJsonWriter.write(p)).status()==TRUE_CONDITIONALLY,"Relation et effets indépendants de la détention");
  var old=new Narrative(source,n.entities(),n.agents(),n.objects(),n.initial(),n.events(),new Query("located",List.of("e0","e1"),-1,1,true,span));
  ok(new StructuredReasoningService(new IntegratedQuestionCycle()).askJson(StructuredJsonWriter.write(new EventLogicCompiler(registry).compile(old).problem())).status()==FALSE_CONDITIONALLY,"Exclusivité générique sur une autre position argumentale");
  var lexicon=new FrenchEventTranslator(List.of(new FrenchEventTranslator.Verb("confié","transfer",List.of("source","object","target"),false)));
  ok(lexicon.translate("Aline a confié le carnet à Boris. Qui a le carnet ?").complete(),"Extension lexicale déclarative sans phrase réponse");
 }
 private static void console()throws Exception{
  var file=java.nio.file.Files.createTempFile("ivoc628-",".tsv");java.nio.file.Files.delete(file);
  var bytes=new ByteArrayOutputStream();String input="Nadia a donné le dossier à Hugo. Qui a le dossier ?\n:revoir Q1\n:quitter\n";
  OntologicalConsole.runWeb(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),new PrintStream(bytes,true,StandardCharsets.UTF_8));
  String output=bytes.toString(StandardCharsets.UTF_8);ok(output.contains("Réponse sous les conventions narratives : hugo")&&output.contains("JSON sémantique événementiel"),"Console principale raccordée");java.nio.file.Files.deleteIfExists(file);
 }
 public static void main(String[] args)throws Exception{base();abstain();differential();customSchema();console();System.out.println("EVENT-NARRATIVE-0628 OK - "+checks+" contrôles");}
}
