package fr.ivorama.cognitive;
import java.time.*;
import java.net.URI;
import java.util.*;
import java.io.IOException;
/** Documents simulés : contrôles des contrats et du cycle, réseau réel testé séparément. */
public final class Lexical0640Test {
 static int checks;static void ok(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static final class MutableClock extends Clock {
  Instant now=Instant.parse("2026-10-19T05:30:00Z");
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
 }
 static WebSource.Hit hit(String title,String text,Instant now){return new WebSource.Hit(URI.create("https://fr.wikipedia.org/wiki/"+title),title,text,now,"Wikipedia SIMULÉE");}
 static void rejects(Runnable r){boolean caught=false;try{r.run();}catch(IllegalArgumentException e){caught=true;}ok(caught,"JSON falsifié refusé");}
 public static void main(String[] args){
  var clock=new MutableClock();int[] calls={0};
  WebSource.Provider provider=q->{calls[0]++;return List.of(hit(q,"Le "+q+" (domaine documentaire) est un domaine de pratique collective.",clock.now));};
  var lexicon=new LexicalKnowledgeService(provider,clock);var tasks=new TextTaskService(new CreativeTextEngine(),17,provider,lexicon);
  for(String concept:List.of("badminton","jardinage","patinage")){
   var result=tasks.execute(DirectiveActs.classify("Compose une phrase sur le "+concept+".").orElseThrow());
   ok(result.status()==TextTaskService.Status.DOCUMENTED_TEXT,"Reprise informative après acquisition");
   ok(result.output().contains("Le "+concept+" est un domaine"),"Recomposition des segments attestés");
   var trace=result.lexical().orElseThrow();ok(trace.state()==LexicalKnowledgeService.State.ENRICHED,"Enrichissement validé");
   ok(trace.entry().orElseThrow().gender()==FrenchAgreement.Gender.MASCULINE,"Genre du sujet attesté");
   ok(lexicon.read(trace.json()).equals(trace),"JSON relu");rejects(()->lexicon.read(trace.json().replace("DOCUMENTARY_CANDIDATE","FACT")));
  }
  ok(calls[0]==3,"Un appel par nouveau concept");
  var poetry=tasks.execute(DirectiveActs.classify("Compose une phrase poétique sur le badminton.").orElseThrow());
  ok(poetry.status()==TextTaskService.Status.CREATED&&poetry.output().contains("badminton"),"Nom sourcé dans un plan de fiction");
  ok(poetry.lexical().orElseThrow().cacheHit()&&calls[0]==3,"Réutilisation du cache");
  ok(tasks.revisit(poetry.lexical().orElseThrow().id()).output().equals(poetry.output())&&calls[0]==3,"Révision reproductible sans réseau");
  var withdrawn=tasks.exclude(poetry.lexical().orElseThrow().id(),poetry.lexical().orElseThrow().documents().getFirst().url());
  ok(withdrawn.status()==TextTaskService.Status.NEEDS_INFORMATION&&withdrawn.composition().isEmpty(),"Retrait suspend le résultat");
  ok(lexicon.lookup("badminton").state()==LexicalKnowledgeService.State.EXCLUDED,"Pas de réactivation silencieuse");
  var entry=lexicon.lookup("jardinage");clock.now=clock.now.plus(Duration.ofHours(25));
  ok(lexicon.revisit(entry.id()).state()==LexicalKnowledgeService.State.EXPIRED,"Expiration");ok(calls[0]==3,"Révision sans appel");
  ok(lexicon.lookup("jardinage").state()==LexicalKnowledgeService.State.ENRICHED&&calls[0]==4,"Nouvelle demande renouvelle la source");
  // The router owns a wall-clock lexical service: its separate fixture must have a fresh retrieval time.
  // The injected MutableClock above remains responsible for the deterministic expiry tests.
  WebSource.Provider routerProvider=q->{calls[0]++;return List.of(hit(q,"Le "+q+" (domaine documentaire) est un domaine de pratique collective.",Instant.now()));};
  var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,routerProvider);
  for(String prompt:List.of("Explique moi ce que signifie le patinage","Explique-moi ce que signifie le patinage","Définis le patinage"))
   ok(router.respond(prompt).execution().orElseThrow().status()==TextTaskService.Status.DOCUMENTED_TEXT,"Intention de définition : "+prompt);
  ok(cycle.questionActCount()==0&&cycle.premises().isEmpty(),"Pas de question fictive ni vérité importée");
  int before=calls[0];router.respond("Compose une phrase sur la mer et la forêt.");ok(calls[0]==before,"Ambiguïté de tâche avant recherche");
  ok(new LexicalKnowledgeService(q->{throw new IOException("offline");},clock).lookup("astrolabe").state()==LexicalKnowledgeService.State.WEB_FAILED,"Panne réseau explicite");
  boolean bug=false;try{new LexicalKnowledgeService(q->{throw new IllegalStateException("bug");},clock).lookup("astrolabe");}catch(IllegalStateException e){bug=true;}ok(bug,"Bug interne non masqué");
  ok(new LexicalKnowledgeService(q->List.of(),clock).lookup("astrolabe").state()==LexicalKnowledgeService.State.NO_DOCUMENTS,"Aucun document, aucune invention");
  ok(new LexicalKnowledgeService(q->List.of(hit("voisin","Le voisin est un domaine.",clock.now)),clock).lookup("astrolabe").state()==LexicalKnowledgeService.State.AMBIGUOUS,"Titre voisin non choisi");
  for(String text:List.of("Le quasar est un objet","Le quasar n'est pas un objet.","Le quasar qui est un objet est un phénomène.","Selon certains le quasar est un objet."))
   ok(new LexicalKnowledgeService(q->List.of(hit("quasar",text,clock.now)),clock).lookup("quasar").entry().isEmpty(),"Fragment non validé : "+text);
  var elided=new LexicalKnowledgeService(q->List.of(hit("astrolabe","L'astrolabe est un instrument ancien.",clock.now)),clock).lookup("astrolabe");
  ok(elided.entry().isPresent()&&elided.entry().orElseThrow().noun().isEmpty(),"Élision ne prouve pas le genre");
  // Les routes réelles utilisent l'horloge système ; remettre la date documentaire au présent.
  clock.now=Instant.now();
  var analysis=router.respond("Analyse ce texte : « Le patinage est difficile. »").execution().orElseThrow();
  ok(analysis.webContext().isPresent()&&!analysis.webContext().orElseThrow().lexical().isEmpty(),"Service lexical partagé pour l'analyse");
  ok(analysis.analysis().orElseThrow().observations().stream().anyMatch(o->o.kind().equals("CONTEXTE LEXICAL SOURCÉ")),"Contexte réinjecté dans l'explication, sans faux sens littéraire");
  ok(cycle.premises().isEmpty()&&cycle.questionActCount()==0,"Analyse reste locale");
  System.out.println("Lexical0640Test OK : "+checks+" contrôles");
 }
}
