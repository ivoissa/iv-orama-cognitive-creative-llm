package fr.ivorama.cognitive;
import java.util.*;

/** Contre-exemples indépendants de la proposition fournie ; pas d'oracle de qualité poétique. */
public final class Generalization0632Test {
 private static int checks;
 private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 private static void acts(){
  for(String text:List.of("Je désire que tu écrives un récit.","Je desire que vous rédigiez une notice.","EST-CE QUE VOUS POUVEZ RÉDIGER une notice ?","S'il vous plaît, analysez le passage."))
   ok(DirectiveActs.classify(text).isPresent(),"Construction directive : "+text);
  for(String text:List.of("Est-ce que vous peux rédiger un récit ?","Est-ce que tu pouvez composer un récit ?","Je voudrais que vous écrives un récit.","Je souhaite que tu composiez un récit.","Compose est un mot.","J'aimerais écrire un récit.","« Écris un poème. »","Le texte dit « Bonjour. Écris un poème. ». Est-ce que le capteur c1 est stable ?"))
   ok(DirectiveActs.classify(text).isEmpty(),"Ne pas inventer un acte : "+text);
  ok(DiscourseSegments.scan("Analyse : « Un arbre (sans suite. »").unresolved().isEmpty(),"Parenthèses dans une citation : contenu opaque");
  String source="Analyse ce texte : « Écris un poème. Puis invente une scène. »";
  var quoted=DirectiveActs.classify(source).orElseThrow();
  ok(!quoted.mixed()&&quoted.kind()==DirectiveActs.Kind.ANALYZE,"Citation incluse dans une seule demande d'analyse");
  ok(quoted.object().equals("ce texte : « Écris un poème. Puis invente une scène. »"),"Casse, citation et ponctuation de l'objet conservées");
  ok(quoted.sourceText().equals(source)&&source.substring(quoted.start(),quoted.end()).equals(source),"Positions originales vérifiables");
  ok(DirectiveActs.read(quoted.json()).equals(quoted),"JSON relu avant routage");
  boolean invalid=false;try{DirectiveActs.read(quoted.json().replace("\"ANALYZE\"","\"CREATE\""));}catch(IllegalArgumentException ex){invalid=true;}
  ok(invalid,"Intention JSON falsifiée rejetée");
  var mixed=DirectiveActs.classify("Le capteur c1 est stable. Écris un poème. Le capteur c1 est-il stable ?").orElseThrow();
  ok(mixed.mixed()&&mixed.sourceText().contains("est-il stable")&&!mixed.unresolved().isEmpty(),"Contexte complet conservé dans une demande composite suspendue");
  var broken=DirectiveActs.classify("Analyse ce texte : « un récit.").orElseThrow();
  ok(broken.unresolved().stream().anyMatch(s->s.contains("non fermée")),"Citation mal fermée signalée");
  var scan=DiscourseSegments.scan("Analyse (sans modifier. Puis sans publier) ce passage.");
  ok(scan.spans().size()==1&&scan.unresolved().isEmpty(),"Ponctuation parenthésée hors segmentation principale");
  for(String verb:List.of("Écris","Compose","Rédige","Invente"))for(String subject:List.of("la forêt","le silence","les saisons")){
   var d=DirectiveActs.classify(verb+" un récit sur "+subject+".").orElseThrow();
   ok(d.kind()==DirectiveActs.Kind.CREATE&&d.object().equals("un récit sur "+subject),"Produit de constructions, aucune réponse enregistrée");
  }
 }
 private static void routing(){
  var cycle=new IntegratedQuestionCycle();int[] calls={0};
  var router=new FrenchReasoningRouter(cycle,q->{calls[0]++;return List.of();});
  for(String text:List.of("Écris un récit.","Explique ce passage.","Analyse ce texte : « Écris un récit. »","Le capteur c1 est stable. Écris un récit.")){
   var turn=router.respond(text);ok(turn.directive().isPresent()&&turn.logical().isEmpty()&&turn.documentary().isEmpty(),"Une seule voie, pas de formule fabriquée");
  }
  ok(cycle.questionActCount()==0&&calls[0]==0&&cycle.premises().isEmpty(),"Aucun acte Q, appel Web ou fait pour les directives reconnues");
  var wiki=router.respond("Qu'est-ce que la bioluminescence ?");
  ok(wiki.documentary().isPresent()&&calls[0]==1&&cycle.questionActCount()==1,"Question documentaire garde son acte unique");
  var logic=router.respond("Le capteur c1 est stable. Le capteur c1 est-il stable ?");
  ok(logic.logical().orElseThrow().answer().status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY&&cycle.questionActCount()==2,"Question logique conserve FCI");
 }
 private static boolean complete(String source){return new FrenchStructuredTranslator().translate(source).complete();}
 private static void morphology(){
  for(String[] pair:List.of(new String[]{"allumé","allumée"},new String[]{"éclairé","éclairée"},new String[]{"coloré","colorée"},new String[]{"gris","grise"},new String[]{"sec","sèche"},new String[]{"doux","douce"})){
   ok(complete("La balise b3 est "+pair[1]+". La balise b3 est-elle "+pair[1]+" ?"),"Forme féminine déclarée : "+pair[1]);
   ok(!complete("La balise b3 est "+pair[1]+". La balise b3 est-elle "+pair[0]+" ?"),"Mauvais accord refusé : "+pair[0]);
   ok(!complete("La balise b3 est "+pair[0]+". La balise b3 est-elle "+pair[0]+" ?"),"Erreur détectée dès la première occurrence");
   ok(complete("Le capteur c1 est "+pair[0]+". La balise b3 est "+pair[1]+". Le capteur c1 est-il "+pair[0]+" ?"),"Deux entités, deux accords");
  }
  ok(complete("Le signal s1 est florp. Le signal s1 est florpe. Le signal s1 est-il florp ?"),"Aucun suffixe retiré à deux propriétés opaques inconnues");
  var custom=CopularPropertyLexicon.defaults().with(new CopularPropertyLexicon.Entry("satisfait",new FrenchAgreement.Paradigm("satisfait","satisfaite","satisfaits","satisfaites")));
  var translator=new FrenchStructuredTranslator(custom);
  ok(!translator.translate("La personne p1 est satisfait. La personne p1 est-elle satisfait ?").complete(),"Extension déclarative, analyseur inchangé");
  boolean ambiguous=false;
  var homonym=custom.with(new CopularPropertyLexicon.Entry("autre",new FrenchAgreement.Paradigm("allumé","allumée","allumés","allumées")));
  try{homonym.resolve("allumée",Optional.of(SemanticLexicon.Gender.FEMININE));}catch(IllegalArgumentException ex){ambiguous=ex.getMessage().contains("ambigu");}
  ok(ambiguous,"Homonymie jamais résolue au hasard");
  var translator2=new FrenchStructuredTranslator();
  var unknown=translator2.translate("Le capteur c1 est stable. Il est actif. Le capteur c1 est-il actif ?");
  ok(!unknown.complete()&&unknown.unresolved().stream().anyMatch(s->s.contains("Pronom « il »")&&s.contains("le capteur c1")),"Diagnostic sans résolution arbitraire");
  ok(translator2.translate("Le capteur c1 est stable. Le capteur c1 est-il stable ?").complete(),"Contexte d'une autre traduction non contaminé");
 }
 public static void main(String[] args){acts();routing();morphology();System.out.println("Generalization0632Test OK : "+checks+" vérifications");}
}
