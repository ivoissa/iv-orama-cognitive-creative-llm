package fr.ivorama.cognitive;
import java.util.*;
/** Tests de propriétés exposés ; aucun accès réseau, aucune réponse spécifique en production. */
public final class Corrections0636Test {
 private static int checks;
 private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 private static TextTaskService.Result run(String input){return new FrenchReasoningRouter(new IntegratedQuestionCycle(),q->List.of()).respond(input).execution().orElseThrow();}
 private static void reasoning(){
  var analyzer=new TextContextAnalysis(CreativeTextEngine.defaults());
  for(String noun:List.of("capteur c7","module m8","signal s2","dossier d5")){
   String entity="Le "+noun;List<String> states=List.of("actif","disponible","stable","complet");
   for(int length=1;length<=4;length++){
    StringBuilder source=new StringBuilder(entity+" n'est pas valide.");
    for(int i=0;i<length;i++)source.append(" Si ").append(entity.toLowerCase(Locale.ROOT)).append(" est ").append(states.get(i)).append(", alors ").append(entity.toLowerCase(Locale.ROOT)).append(" est ").append(i==length-1?"valide":states.get(i+1)).append('.');
    var result=analyzer.analyze(source.toString());
    ok(result.status().equals("STRUCTURE_RECOGNIZED"),"Chaîne totalement interprétée");
    ok(result.inferences().size()==length,"Toutes les négations de la chaîne, pas seulement les conséquents");
    ok(result.inferences().stream().allMatch(i->i.proofChecked()&&i.explanation().startsWith("négation")&&i.premiseIds().contains("txt_p0")),"Chaque résultat négatif est vérifié et dépend du fait négatif");
   }
  }
  var unrelated=analyzer.analyze("Le module m1 n'est pas stable. Si le module m1 est actif, alors le module m1 est disponible.");
  ok(unrelated.inferences().isEmpty(),"Pas de négation par absence d'information");
  var inconsistent=analyzer.analyze("Le module m1 est actif. Le module m1 n'est pas actif. Si le module m1 est actif, alors le module m1 est stable.");
  ok(inconsistent.status().equals("INCONSISTENT_TEXT")&&inconsistent.inferences().isEmpty(),"Pas d'explosion sur contradiction");
  var partial=analyzer.analyze("Le module m1 n'est pas stable. Si le module m1 est actif, alors le module m1 est stable. Un passage libre dépasse le fragment.");
  ok(partial.inferences().isEmpty()&&partial.status().equals("PARTIAL_ANALYSIS"),"Lecture partielle reste sans conclusions globales");
  var conjunction=analyzer.analyze("Le module m1 est disponible et le module m1 est stable.");
  ok(conjunction.inferences().size()==2,"Élimination de conjonction vers deux atomes prouvés");
  StringBuilder large=new StringBuilder();for(int i=0;i<17;i++)large.append("Si le module m").append(i).append(" est actif, alors le module m").append(i).append(" est stable. ");
  ok(analyzer.analyze(large.toString()).status().equals("RESOURCE_LIMIT"),"Dépassement du budget de cibles signalé");
 }
 private static void requests(){
  var compound=CreativeRequest.parse("un poème sur la mer qui parle de l'enfance en trois vers rimés");
  ok(compound.themes().equals(List.of("mer","enfance")),"Deux thèmes extraits sans avaler les contraintes");
  ok(compound.lines().orElseThrow()==3&&compound.rhyme()&&compound.unresolved().isEmpty(),"Nombre et rime reconnus séparément");
  ok(compound.unsupported().size()==2,"Combinaison thématique et rime explicitement non prises en charge");
  ok(CreativeRequest.read(compound.json()).equals(compound),"JSON effectivement relu");
  boolean rejected=false;try{CreativeRequest.read(compound.json().replace("\"lines\":3","\"lines\":4"));}catch(IllegalArgumentException ex){rejected=true;}
  ok(rejected,"Falsification du nombre rejetée");
  for(String number:List.of("1","un","deux","3","quatre")){
   var request=CreativeRequest.parse("un poème en "+number+" vers sur la nuit");
   var result=run("Écris "+request.source()+".");
   ok(result.status()==TextTaskService.Status.CREATED,"Nombre de lignes supporté");
   ok(result.composition().orElseThrow().text().lines().count()==request.lines().orElseThrow(),"Contrainte mesurée sur la sortie");
   ok(CreativeRequest.read(result.requestJson()).equals(request),"Requête utilisée et JSON retourné identiques");
  }
  for(String tail:List.of("en cinq vers","en 999999999999999999999 vers","en trois vers en deux vers","avec des rimes","sans rimes","et triste","de dix mots","avec un ton joyeux","sans violence")){
   var result=run("Écris un poème sur la mer "+tail+".");
   ok(result.status()==TextTaskService.Status.NEEDS_INFORMATION&&result.composition().isEmpty(),"Contrainte non supportée ne produit rien : "+tail);
   ok(!CreativeRequest.read(result.requestJson()).unsupported().isEmpty(),"Résidu ou contrainte conservé : "+tail);
  }
  ok(run("Écris une phrase en trois vers sur la mer.").composition().isEmpty(),"Contradiction de forme non exécutée");
  ok(run("Écris un poème sur la photosynthèse en trois vers.").composition().isEmpty(),"Aucun lexème nouveau inventé");
  var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,q->{throw new AssertionError("Pas de réseau pour ces corrections");});
  router.respond("Écris un poème en trois vers sur la mer.");router.respond("Explique ce texte : « Le module m1 n'est pas stable. Si le module m1 est actif, alors le module m1 est stable. »");
  ok(cycle.premises().isEmpty()&&cycle.questionActCount()==0,"Noyau FCI et faits globaux préservés");
 }
 public static void main(String[] args){reasoning();requests();System.out.println("Corrections0636Test OK : "+checks+" contrôles ; réseau non utilisé");}
}
