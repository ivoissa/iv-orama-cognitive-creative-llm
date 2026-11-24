package fr.ivorama.cognitive;
import java.net.URI;
import java.time.Instant;
import java.util.*;
public final class Conversation0622Test {
 private static int checks;
 private static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 private static WebSource.Hit hit(String excerpt){return new WebSource.Hit(URI.create("https://example.org/document"),"Document simulé",excerpt,Instant.parse("2026-10-16T21:00:00Z"),"fake");}
 private static void explanation(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();int[] calls={0};
  var d=new OntologyConversation(g,c,q->{calls[0]++;return List.of();});
  var a=d.respond("Pourquoi un carré est-il un rectangle ?");
  ok(a.status()==OntologyConversation.Status.ANSWER,"Intention explicative");
  ok(a.text().contains("math_1") && a.text().contains("classe incluse dans rectangle"),"Justification issue du graphe");
  ok(a.text().contains("aucune cause physique"),"Justification pas causalité");
  ok(c.ontologyRepresentation(a.questionId()).orElseThrow().subject().name().equals("carre"),"Pourquoi non inclus dans sujet");
  ok(c.questionActCount()==1 && calls[0]==0,"Acte unique, pas de Web");
  var follow=d.respond("Et pourquoi ?");
  ok(c.questionActCount()==2 && !a.questionId().equals(follow.questionId()),"Explication de suivi = nouvel acte lié");
  ok(follow.text().contains("math_1") && calls[0]==0,"Suivi fondé sur preuve actuelle");
  ok(c.journal().stream().anyMatch(e->e.detail().contains("Justification de "+a.questionId())),"Lien FCI journalisé");
  g.retract("math_1");
  var revised=d.revisit(a.questionId());
  ok(revised.text().contains("UNKNOWN") && !revised.text().contains("[math_1 ;"),"Retrait retire justification");
  ok(calls[0]==0,"Révision ne recherche pas en silence");
  var before=d.respond("Pourquoi ?");ok(before.text().contains("Aucune preuve locale positive"),"Pas de justification inventée après retrait");
 }
 private static void clarification(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();int[] calls={0};
  var d=new OntologyConversation(g,c,q->{calls[0]++;return List.of();});
  long v=g.version();var a=d.respond("Mercure est-il un métal ?");
  ok(a.status()==OntologyConversation.Status.NEEDS_CLARIFICATION && a.choices().size()==2,"Ambiguïté explicite");
  ok(c.ontologyRepresentation(a.questionId()).isEmpty() && calls[0]==0,"Pas RSI ni Web avant choix");
  var wrong=d.respond("le troisième");ok(wrong.status()==OntologyConversation.Status.NEEDS_CLARIFICATION,"Choix invalide refusé");
  ok(wrong.questionId().equals(a.questionId()) && c.questionActCount()==1,"Pas de nouvel Ask pour choix invalide");
  var chosen=d.respond("l'élément chimique");
  ok(chosen.questionId().equals(a.questionId()) && c.questionActCount()==1,"Même acte après choix valide");
  ok(chosen.text().contains("LOCAL_SUPPORTED") && chosen.text().contains("chimie_1"),"Réponse chimie");
  ok(c.ontologyRepresentation(a.questionId()).orElseThrow().subject().name().equals("mercure_element"),"RSI du sens choisi");
  ok(g.version()==v,"Clarification ne modifie pas les faits");
  ok(d.respond("Pourquoi ?").text().contains("chimie_1"),"Contexte désambiguïsé");
  g.sources().setStatus("demo",KnowledgeSourceRegistry.Status.REVOKED);
  ok(d.revisit(a.questionId()).text().contains("UNKNOWN"),"Révocation propagée");
  ok(calls[0]==0,"Réévaluation sans Web");
 }
 private static void unknownAndFailures(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();List<String> queries=new ArrayList<>();
  var d=new OntologyConversation(g,c,q->{queries.add(q);return List.of(hit("La Lune est un satellite."));});
  long v=g.version();int nodes=g.nodes().size();
  var a=d.respond("La Lune est-elle un satellite ?");
  ok(a.status()==OntologyConversation.Status.DOCUMENTS_ONLY,"Recherche malgré concepts absents");
  ok(queries.equals(List.of("lune satellite")),"Requête issue des termes analysés");
  ok(a.documents().size()==1 && a.text().contains("pas une conclusion"),"Extrait cité sans promotion");
  ok(g.version()==v && g.nodes().size()==nodes && g.candidates("lune").isEmpty(),"Aucun concept appris silencieusement");
  ok(c.ontologyRepresentation(a.questionId()).isEmpty(),"Pas RSI typée sans type déclaré");
  ok(d.revisit(a.questionId()).status()==OntologyConversation.Status.NEEDS_VOCABULARY && queries.size()==1,"Révision ne relance pas recherche");
  ok(d.respond("Pourquoi ?").status()==OntologyConversation.Status.NO_CONTEXT,"Pas de contexte résolu inventé");
  var fail=new OntologyConversation(g,new IntegratedQuestionCycle(),q->{throw new java.io.IOException("secret API_KEY=DO_NOT_ECHO");}).respond("La Lune est-elle un satellite ?");
  ok(fail.status()==OntologyConversation.Status.WEB_FAILED && !fail.text().contains("DO_NOT_ECHO"),"Erreur explicite sans message sensible");
  var bad=new OntologyConversation(g,new IntegratedQuestionCycle(),q->List.of(new WebSource.Hit(URI.create("http://evil.example"),"x","x",Instant.now(),"fake"))).respond("La Lune est-elle un satellite ?");
  ok(bad.status()==OntologyConversation.Status.NEEDS_VOCABULARY && bad.documents().isEmpty(),"HTTP filtré");
  var stale=new OntologyConversation(g,new IntegratedQuestionCycle(),q->{g.alias("nouveau", "soleil");return List.of(hit("texte"));}).respond("La Lune est-elle un satellite ?");
  ok(stale.status()==OntologyConversation.Status.STALE_CONTEXT && stale.documents().isEmpty(),"Recherche périmée abandonnée");
  var inverse=new OntologyConversation(g,new IntegratedQuestionCycle(),q->List.of()).respond("Tout rectangle est-il un carré ?");
  ok(inverse.status()==OntologyConversation.Status.UNDETERMINED && inverse.text().contains("UNKNOWN"),"Pas inversion abusive ni réponse négative inventée");
  var knownFailure=new OntologyConversation(g,new IntegratedQuestionCycle(),q->{throw new java.io.IOException();}).respond("Tout rectangle est-il un carré ?");
  ok(knownFailure.status()==OntologyConversation.Status.WEB_FAILED,"Une panne n'est pas affichée comme réponse obtenue");
  var fresh=new OntologyConversation(g,new IntegratedQuestionCycle(),q->List.of());
  ok(fresh.respond("Pourquoi ?").status()==OntologyConversation.Status.NO_CONTEXT,"Suivi sans antécédent");
  ok(fresh.respond("Comment va-t-il ?").status()==OntologyConversation.Status.UNSUPPORTED,"Autres intentions non inventées");
 }
 public static void main(String[] args){explanation();clarification();unknownAndFailures();System.out.println("CONVERSATION-0622 OK - "+checks+" contrôles");}
}
