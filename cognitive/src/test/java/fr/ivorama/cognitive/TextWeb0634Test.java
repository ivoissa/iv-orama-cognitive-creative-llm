package fr.ivorama.cognitive;
import java.util.*;
import java.net.URI;
import java.time.Instant;

/** Fournisseurs SIMULÉS : protocole Web -> hypothèses -> preuve, pas vérification du contenu réel Wikipédia. */
public final class TextWeb0634Test {
 private static int checks;
 private static void ok(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
 private static WebSource.Hit hit(String title,String excerpt){return new WebSource.Hit(URI.create("https://fr.wikipedia.org/?curid=999999001"),title,excerpt,Instant.now(),"SIMULATED FIXTURE");}
 private static String source(String concept){return "Si la "+concept+" est un phénomène, alors la "+concept+" est décrite.";}
 private static void inference(){
  for(String concept:List.of("capillarité","fluorescence","diffraction","sérendipité")){
   int[] calls={0};var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,q->{calls[0]++;return List.of(hit(concept,"La "+concept+" est un phénomène physique."));});
   var response=router.respond("Explique ce texte : « "+source(concept)+" »").execution().orElseThrow();var web=response.webContext().orElseThrow();
   ok(response.status()==TextTaskService.Status.PROVISIONAL_ANALYSIS,"Réévaluation explicitement provisoire");
   ok(web.baseline().inferences().isEmpty()&&web.assessed().inferences().size()==1,"La prémisse documentaire rend une conséquence accessible");
   ok(web.proposals().get(0).premise().evidence().origin()==StructuredLogic.Origin.WEB_EXCERPT_UNVERIFIED,"Origine Web jamais déclarative");
   ok(web.assessed().inferences().get(0).proofChecked()&&web.assessed().inferences().get(0).premiseIds().contains("web_context_1"),"Preuve contrôlée dépend de la source externe");
   ok(web.baseline().document().propositions().size()==1&&cycle.premises().isEmpty()&&cycle.questionActCount()==0,"Aucun acte Q ou fait global ajouté, document original intact");
   var term=web.terms().get(0);ok(source(concept).substring(term.start(),term.end()).equals(term.text()),"Recherche reliée à un passage du texte");
   ok(calls[0]==1,"Une recherche du concept, pas du texte complet");
   ok(router.revisitTextTask(web.taskId()).analysis().orElseThrow().inferences().size()==1&&calls[0]==1,"Révision sans réseau");
   var excluded=router.excludeTextSource(web.taskId(),web.documents().get(0).url());
   ok(excluded.analysis().orElseThrow().inferences().isEmpty()&&excluded.webContext().orElseThrow().proposals().isEmpty()&&calls[0]==1,"Retrait de source retire la conséquence sans nouvel appel");
   ok(router.revisitTextTask(web.taskId()).analysis().orElseThrow().inferences().isEmpty(),"Exclusion conservée lors de la révision");
  }
 }
 private static void abstention(){
  String concept="capillarité";
  for(var doc:List.of(hit("Capillarité (autre sens)","La capillarité est un phénomène physique."),hit(concept,"La capillarité serait un phénomène physique."),hit(concept,"La capillarité est un phénomène physique"),hit(concept,"La capillarité n'est pas un phénomène physique."),hit(concept,"La capillarité est un autre phénomène."))){
   var router=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of(doc));
   var web=router.respond("Explique ce texte : « "+source(concept)+" »").execution().orElseThrow().webContext().orElseThrow();
   ok(web.state()==TextWebContextService.State.DOCUMENTS_ONLY&&web.proposals().isEmpty()&&web.assessed().inferences().isEmpty(),"Pas de traduction forcée : "+doc.excerpt());
  }
  var router=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of(hit(concept,"La capillarité est un phénomène physique.")));
  var partial=router.respond("Analyse ce texte : « "+source(concept)+" Le phénomène traverse les souvenirs. »").execution().orElseThrow().webContext().orElseThrow();
  ok(partial.assessed().status().equals("PARTIAL_ANALYSIS")&&partial.assessed().inferences().isEmpty(),"Le Web ne répare pas silencieusement une lecture partielle");
  var conflict=router.respond("Explique ce texte : « La capillarité n'est pas un phénomène. "+source(concept)+" »").execution().orElseThrow().webContext().orElseThrow();
  ok(conflict.assessed().status().equals("INCONSISTENT_TEXT")&&conflict.assessed().inferences().isEmpty(),"Contradiction texte/source suspend les conclusions");
  var recovered=router.excludeTextSource(conflict.taskId(),conflict.documents().get(0).url());
  ok(!recovered.analysis().orElseThrow().status().equals("INCONSISTENT_TEXT"),"Source retirée : conflit supprimé");
 }
 private static void conflictingSources(){
  var first=hit("capillarité","La capillarité est un phénomène physique.");
  var second=new WebSource.Hit(URI.create("https://fr.wikipedia.org/?oldid=999999002"),"capillarité","La capillarité est un processus physique.",Instant.now(),"SIMULATED FIXTURE");
  var router=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of(first,second));
  var answer=router.respond("Explique ce texte : « Si la capillarité est un phénomène, alors la capillarité n'est pas un processus. »").execution().orElseThrow().webContext().orElseThrow();
  ok(answer.proposals().size()==2&&answer.assessed().status().equals("INCONSISTENT_TEXT")&&answer.assessed().inferences().isEmpty(),"Sources incompatibles : aucune sélection opportuniste");
  var revised=router.excludeTextSource(answer.taskId(),second.url());
  ok(revised.analysis().orElseThrow().inferences().size()==1,"Exclusion ciblée : reprise de la conséquence de la source restante");
 }
 private static void protocol(){
  var failure=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->{throw new java.io.IOException("fixture");}).respond("Explique ce texte : « "+source("capillarité")+" »").execution().orElseThrow().webContext().orElseThrow();
  ok(failure.state()==TextWebContextService.State.WEB_FAILED&&failure.proposals().isEmpty(),"Panne Web distincte d'une réponse négative");
  var noDoc=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of()).respond("Explique ce texte : « "+source("capillarité")+" »").execution().orElseThrow().webContext().orElseThrow();
  ok(noDoc.state()==TextWebContextService.State.NO_DOCUMENTS,"Aucun document distinct d'une panne");
  int[] calls={0};var bounded=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->{calls[0]++;return List.of();}).respond("Analyse ce texte : « La capillarité, la fluorescence et la diffraction sont étudiées. »").execution().orElseThrow().webContext().orElseThrow();
  ok(calls[0]==2&&bounded.terms().size()==2,"Budget de deux recherches");
  var evil=new WebSource.Hit(URI.create("https://fr.wikipedia.org.evil.test/"),"capillarité","La capillarité est un phénomène physique.",Instant.now(),"SIMULATED");
  ok(!TextWebContextService.valid(evil),"Domaine voisin refusé");
  var local=new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->{throw new AssertionError("Aucun réseau pour identifiants locaux ou concepts déjà connus");});
  ok(local.respond("Explique ce texte : « Le module m1 est actif. »").execution().orElseThrow().webContext().orElseThrow().state()==TextWebContextService.State.LOCAL_ONLY,"Identifiant local non cherché sur Wikipédia");
  ok(local.respond("Écris une phrase poétique sur la mer.").execution().orElseThrow().composition().isPresent(),"Création préservée sans recherche indiscriminée");
  for(int i=0;i<64;i++)local.respond("Explique ce texte : « Le module m1 est actif. »");
  ok(local.textTaskIds().size()==64,"Mémoire bornée sans suppression silencieuse de tâches");
 }
 public static void main(String[] args){inference();abstention();conflictingSources();protocol();System.out.println("TextWeb0634Test OK : "+checks+" contrôles, fournisseurs SIMULÉS ; HTTP réel séparé");}
}
