package fr.ivorama.cognitive;
import java.time.*;
import java.util.*;
public final class Contextual0624Test {
 private static int checks;
 private static void ok(boolean b,String text){checks++;if(!b)throw new AssertionError(text);}
 private static final String ORIGINAL="je suis dans une maison sans fenêtre ; est ce que je vois le soleil ?";
 private static void reasoning(){
  var r=new ContextualVisibilityReasoner();var f=r.analyze(ORIGINAL).orElseThrow();var a=r.assess(f);
  ok(f.premises().size()==1 && f.target().equals("soleil") && f.enclosed(),"Contexte et question séparés");
  ok(a.finding()==ContextualVisibilityReasoner.Finding.UNDETERMINED,"Sans fenêtre ne suffit pas");
  ok(a.visible().orElseThrow().directVisibility() && !a.invisible().orElseThrow().directVisibility(),"Deux témoins incompatibles de réponse");
  ok(a.visible().orElseThrow().values().get(ContextualVisibilityReasoner.Variable.OPENING) || a.visible().orElseThrow().values().get(ContextualVisibilityReasoner.Variable.TRANSPARENT),"Témoin visible possède chemin");
  String enclosed="je suis dans une cabine; toutes les parois sont opaques; il n'y a aucune ouverture; ";
  var negative=r.assess(r.analyze(enclosed+"est-ce que je vois la lune ?").orElseThrow());
  ok(negative.finding()==ContextualVisibilityReasoner.Finding.IMPOSSIBLE_CONDITIONALLY && negative.visible().isEmpty(),"Enceinte opaque fermée cible extérieure");
  var conflict=r.assess(r.analyze(enclosed+"la porte est ouverte; je vois la lune ?").orElseThrow());
  ok(conflict.finding()==ContextualVisibilityReasoner.Finding.INCONSISTENT && conflict.admissibleModels()==0,"Ouvert et sans ouverture contradiction");
  var door=r.assess(r.analyze("je suis dans une pièce; la porte est ouverte; je vois le soleil ?").orElseThrow());
  ok(door.finding()==ContextualVisibilityReasoner.Finding.UNDETERMINED,"Porte ouverte pas preuve de visibilité");
  var internal=r.assess(r.analyze(enclosed+"je vois le tableau ?").orElseThrow());
  ok(internal.finding()==ContextualVisibilityReasoner.Finding.UNDETERMINED,"Cible générique pas supposée extérieure");
  var external=r.assess(r.analyze(enclosed+"l'objet est dehors; je vois le tableau ?").orElseThrow());
  ok(external.finding()==ContextualVisibilityReasoner.Finding.IMPOSSIBLE_CONDITIONALLY,"Position déclarée de cible générique");
  var positive=r.assess(r.analyze("je suis dans une salle; la porte est ouverte; la ligne de visée est libre; la cible est visible à cet instant; je peux voir normalement; je vois le jardin ?").orElseThrow());
  ok(positive.finding()==ContextualVisibilityReasoner.Finding.POSSIBLE_CONDITIONALLY && positive.invisible().isEmpty(),"Toutes conditions suffisantes déclarées");
  for(String place:List.of("une maison","une cabine","un hangar"))
   for(String target:List.of("le soleil","la lune","les étoiles"))
    ok(r.assess(r.analyze("Je suis dans "+place+" sans fenêtres. Est-ce que je vois "+target+" ?").orElseThrow()).finding()==ContextualVisibilityReasoner.Finding.UNDETERMINED,"Composition lieu/cible");
  var unparsed=r.assess(r.analyze("je suis dans une maison sans porte; je vois le soleil ?").orElseThrow());
  ok(unparsed.finding()==ContextualVisibilityReasoner.Finding.NEEDS_INTERPRETATION,"Ne pas ignorer qualificatif inconnu");
  ok(r.assess(r.analyze("je suis dans une maison qui ne ferme pas sans fenêtre; je vois le soleil ?").orElseThrow()).finding()==ContextualVisibilityReasoner.Finding.NEEDS_INTERPRETATION,"Pas de proposition cachée dans le lieu");
  ok(r.assess(r.analyze("je vois le soleil ?").orElseThrow()).finding()==ContextualVisibilityReasoner.Finding.NEEDS_INTERPRETATION,"Pas de scène inventée");
 }
 private static void dialogue(){
  var g=OntologicalDemoFixture.create();var c=new IntegratedQuestionCycle();int[] calls={0};long version=g.version();int nodes=g.nodes().size();
  var d=new QuestionOrientationDialogue(g,c,q->{calls[0]++;return List.of();},Clock.systemUTC());
  var first=d.respond(ORIGINAL);
  ok(first.status()==QuestionOrientationDialogue.Status.CONTEXT_UNDETERMINED && calls[0]==0,"Pas de recherche arbitraire");
  ok(first.analysis().context().orElseThrow().target().equals("soleil"),"Candidat structuré");
  ok(c.ontologyQuestion(first.questionId()).expression().equals(ORIGINAL) && c.questionActCount()==1,"Acte original conservé");
  ok(c.ontologyRepresentation(first.questionId()).isEmpty(),"Pas de RSI ontologique inventée");
  ok(d.respond("oui").status()==QuestionOrientationDialogue.Status.NEEDS_INTENT && c.questionActCount()==1,"Précision vague refusée même Q");
  var opaque=d.respond("Toutes les parois sont opaques");
  ok(opaque.questionId().equals(first.questionId()) && opaque.status()==QuestionOrientationDialogue.Status.CONTEXT_UNDETERMINED,"Opacité seule laisse ouvertures");
  var closed=d.respond("Il n'y a aucune ouverture");
  ok(closed.questionId().equals(first.questionId()) && closed.status()==QuestionOrientationDialogue.Status.CONTEXT_CONDITIONAL,"Complétions même Q permettent conclusion conditionnelle");
  ok(closed.text().contains("impossible") && closed.text().contains("non vérifiées"),"Pas de certification empirique");
  ok(c.questionActCount()==1 && calls[0]==0 && g.version()==version && g.nodes().size()==nodes,"Aucun fait ni appel ajouté");
  d.respond(ORIGINAL);var fresh=d.respond("Le Soleil est il une étoile ?");
  ok(fresh.text().contains("LOCAL_SUPPORTED"),"Nouvelle question sort du contexte en attente");
  d.respond(ORIGINAL);ok(d.respond("Pourquoi ?").text().contains("NO_CONTEXT"),"Pas reprise d'une ancienne preuve");
  var malformed=d.respond("Je suis dans une maison ; est ce que le cuivre est conducteur ?");
  ok(malformed.status()==QuestionOrientationDialogue.Status.NEEDS_INTENT && calls[0]==0,"Autre contexte pas englouti dans propriété");
  ok(d.respond("Le cuivre est conducteur ; une autre question ?").status()==QuestionOrientationDialogue.Status.NEEDS_INTENT,"Deux propositions non transformées en recherche");
 }
 public static void main(String[] args){reasoning();dialogue();System.out.println("CONTEXT-0624 OK - "+checks+" contrôles");}
}
