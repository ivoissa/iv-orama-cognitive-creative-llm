package fr.ivorama.cognitive;
import java.net.URI;
import java.time.*;
import java.util.*;
public final class QuestionOrientation0623Test {
 private static int checks;
 private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-10-17T22:30:00Z"),ZoneId.of("Europe/Paris"));
 private static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 private static WebSource.Hit hit(){return new WebSource.Hit(URI.create("https://example.org/fixture"),"Document simulé","Extrait simulé, aucune observation validée.",CLOCK.instant(),"fake");}
 private static void dated(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();List<String> queries=new ArrayList<>();
  var d=new QuestionOrientationDialogue(g,c,q->{queries.add(q);return List.of(hit());},CLOCK);long version=g.version();
  String original="quel était la météo aujourd'hui ?";var a=d.respond(original);
  ok(a.status()==QuestionOrientationDialogue.Status.NEEDS_LOCATION,"Météo demande lieu");
  ok(a.analysis().date().orElseThrow().equals(LocalDate.of(2026,10,18)),"Aujourd'hui dépend du fuseau injecté");
  ok(queries.isEmpty() && c.questionActCount()==1,"Aucun Web sans lieu, un acte");
  ok(c.ontologyQuestion(a.questionId()).expression().equals(original),"Expression originale préservée");
  ok(c.questionAnalysis(a.questionId()).orElseThrow().kind()==FrenchQuestionAnalyzer.Kind.DATED_VALUE,"Candidat enregistré");
  ok(c.ontologyRepresentation(a.questionId()).isEmpty(),"Pas RSI ontologique fictive");
  ok(c.questionAct(a.questionId()).state()==IntegratedQuestionCycle.ActState.ORIENTING,"Acte orienté");
  var bad=d.respond("1234");ok(bad.status()==QuestionOrientationDialogue.Status.NEEDS_LOCATION && bad.questionId().equals(a.questionId()),"Lieu invalide même Q");
  var b=d.respond("à Lyon");
  ok(b.questionId().equals(a.questionId()) && c.questionActCount()==1,"Clarification même obligation");
  ok(b.status()==QuestionOrientationDialogue.Status.DOCUMENTS_ONLY && b.documents().size()==1,"Documents pas mesure");
  ok(queries.equals(List.of("meteo Lyon 2026-10-18")),"Requête avec lieu/date");
  ok(b.text().contains("pas une conclusion") && b.text().contains("unité"),"Limite météo explicite");
  d.revisit(a.questionId());ok(queries.size()==1,"Pas recherche implicite");
  var date=d.respond("Quelle est la température à Nantes ?");
  ok(date.status()==QuestionOrientationDialogue.Status.NEEDS_DATE,"Date manquante");
  ok(d.respond("2026-02-30").status()==QuestionOrientationDialogue.Status.NEEDS_DATE,"Date impossible refusée");
  var filled=d.respond("2026-10-16");
  ok(filled.questionId().equals(date.questionId()) && queries.get(1).equals("temperature nantes 2026-10-16"),"Date complétée même Q");
  var both=d.respond("Quelle est l'humidité ?");ok(both.status()==QuestionOrientationDialogue.Status.NEEDS_LOCATION,"Deux informations manquantes");
  ok(d.respond("Brest").status()==QuestionOrientationDialogue.Status.NEEDS_DATE,"Puis demande date");
  ok(d.respond("2026-10-15").questionId().equals(both.questionId()),"Complétions successives même Q");
  ok(g.version()==version,"Recherche ne modifie pas le graphe");
 }
 private static void combinations(){
  var a=new FrenchQuestionAnalyzer(CLOCK);
  for(String subject:List.of("Une mangue","Une poire","Un satellite artificiel"))
   for(String target:List.of("un légume","un fruit","un objet")){
    var f=a.analyze(subject+" est elle "+target+" ?");
    ok(f.kind()==FrenchQuestionAnalyzer.Kind.CLASSIFICATION && f.ontologyCompatible(),"Combinaison de termes sans question enregistrée");
   }
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();List<String> queries=new ArrayList<>();
  var d=new QuestionOrientationDialogue(g,c,q->{queries.add(q);return List.of(hit());},CLOCK);long version=g.version();int nodes=g.nodes().size();
  var mango=d.respond("Une mangue est elle un légume ?");
  ok(mango.status()==QuestionOrientationDialogue.Status.DELEGATED && mango.text().contains("DOCUMENTS_ONLY"),"Mangue orientée vers documentaire historique");
  ok(queries.equals(List.of("mangue legume")),"Requête extraite de la nouvelle question");
  ok(c.ontologyRepresentation(mango.questionId()).isEmpty() && g.nodes().size()==nodes,"Inconnus sans promotion");
  var sun=d.respond("Le Soleil est il une étoile ?");
  ok(sun.text().contains("LOCAL_SUPPORTED") && queries.size()==1,"Inférence historique préservée sans Web");
  ok(c.ontologyRepresentation(sun.questionId()).isPresent(),"RSI uniquement après résolution");
  ok(d.respond("Pourquoi ?").text().contains("Justification logique"),"Suivi logique préservé");
  var prop=d.respond("Le cuivre est il conducteur ?");
  ok(prop.status()==QuestionOrientationDialogue.Status.DOCUMENTS_ONLY && queries.get(1).equals("cuivre conducteur"),"Propriété autre que classification");
  ok(g.version()==version,"Pas d'apprentissage implicite");
  for(String s:List.of("Une mangue n'est pas un légume ?","Si le chat a faim ?","Le chat a faim et soif ?","Comment réparer ce moteur ?"))
   ok(d.respond(s).status()==QuestionOrientationDialogue.Status.NEEDS_INTENT,"Portée non supportée reste explicite");
  ok(a.analyze("Quelle est la météo à Lyon hier demain ?").kind()==FrenchQuestionAnalyzer.Kind.UNSUPPORTED,"Dates relatives multiples refusées");
  Map<String,FrenchQuestionAnalyzer.Scope> lex=new HashMap<>(FrenchQuestionAnalyzer.defaultLexicon());lex.put("luminosité",FrenchQuestionAnalyzer.Scope.PUBLIC_DATED_VALUE);
  var extended=new FrenchQuestionAnalyzer(CLOCK,lex).analyze("Quelle est la luminosité à Dijon hier ?");
  ok(extended.kind()==FrenchQuestionAnalyzer.Kind.DATED_VALUE && extended.date().orElseThrow().equals(LocalDate.of(2026,10,17)),"Lexique extensible sans réponse stockée");
  ok(a.analyze("Quelle est la météo à Lyon 2026-02-30 ?").kind()==FrenchQuestionAnalyzer.Kind.UNSUPPORTED,"Date initiale invalide");
  ok(a.analyze("Quelle est la météo à Lyon hier 2026-10-17 ?").kind()==FrenchQuestionAnalyzer.Kind.UNSUPPORTED,"Deux dates refusées");
 }
 private static void observationAndContext(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();int[] calls={0};
  var d=new QuestionOrientationDialogue(g,c,q->{calls[0]++;return List.of();},CLOCK);
  d.respond("Un carré est-il un rectangle ?");var cat=d.respond("Le chat a t il faim ?");
  ok(cat.status()==QuestionOrientationDialogue.Status.NEEDS_OBSERVATION && calls[0]==0,"Chat : observation pas Web");
  ok(cat.analysis().subject().equals("chat") && cat.analysis().target().equals("faim"),"Décomposition a t il");
  var report=d.respond("Il miaule devant son bol");
  ok(report.questionId().equals(cat.questionId()) && report.status()==QuestionOrientationDialogue.Status.NEEDS_OBSERVATION && report.text().contains("ne suffit pas") && calls[0]==0,"Observation rapportée pas preuve");
  ok(c.ontologyRepresentation(cat.questionId()).isEmpty(),"Aucune assertion faim créée");
  var why=d.respond("Pourquoi ?");
  ok(why.text().contains("NO_CONTEXT"),"Pas réutilisation du carré après question chat");
  ok(d.revisit(why.questionId()).status()==QuestionOrientationDialogue.Status.NEEDS_INTENT,"Question sans contexte reste consultable");
  var server=d.respond("Le serveur est il disponible ?");
  ok(server.status()==QuestionOrientationDialogue.Status.NEEDS_OBSERVATION,"État d'un autre individu");
  var fresh=d.respond("Le Soleil est une étoile");
  ok(!fresh.questionId().equals(server.questionId()) && fresh.text().contains("LOCAL_SUPPORTED"),"Nouvelle question directe remplace attente");
  var amb=d.respond("Mercure est il un métal ?");ok(amb.text().contains("NEEDS_CLARIFICATION"),"Ambiguïté historique");
  var choice=d.respond("l'élément chimique");
  ok(choice.questionId().equals(amb.questionId()) && choice.text().contains("chimie_1"),"Choix conserve Q");
  d.respond("Mercure est il un métal ?");
  ok(d.respond("Le Soleil est une étoile").text().contains("LOCAL_SUPPORTED"),"Nouvelle Q ne complète pas ancienne ambiguïté");
 }
 private static void failures(){
  var g=OntologicalDemoFixture.create();
  var fail=new QuestionOrientationDialogue(g,new IntegratedQuestionCycle(),q->{throw new java.io.IOException("TOKEN_SECRET");},CLOCK).respond("Quelle est la météo à Lyon hier ?");
  ok(fail.status()==QuestionOrientationDialogue.Status.WEB_FAILED && !fail.text().contains("TOKEN_SECRET"),"Erreur sans secret");
  var stale=new QuestionOrientationDialogue(g,new IntegratedQuestionCycle(),q->{g.alias("nouveau","soleil");return List.of(hit());},CLOCK).respond("Quelle est la météo à Lyon hier ?");
  ok(stale.status()==QuestionOrientationDialogue.Status.STALE_RESEARCH && stale.documents().isEmpty(),"Graphe changé résultats rejetés");
  var empty=new QuestionOrientationDialogue(g,new IntegratedQuestionCycle(),q->List.of(new WebSource.Hit(URI.create("http://example.org"),"x","x",CLOCK.instant(),"fake")),CLOCK).respond("Le cuivre est conducteur ?");
  ok(empty.status()==QuestionOrientationDialogue.Status.NO_DOCUMENTS,"HTTP non retenu");
 }
 public static void main(String[] args){dated();combinations();observationAndContext();failures();System.out.println("ORIENTATION-0623 OK - "+checks+" contrôles (fournisseurs simulés)");}
}
