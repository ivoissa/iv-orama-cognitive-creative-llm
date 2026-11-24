package fr.ivorama.cognitive;

import java.util.*;

/** Exposed tests, no prompt-answer table in production; actual pretrained French models. */
public final class TextContext0638Test {
 private static int checks;
 private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
 private static void rejected(Runnable action,String message){boolean rejected=false;try{action.run();}catch(IllegalArgumentException ex){rejected=true;}check(rejected,message);}
 public static void main(String[] args){
  var reader=new FrenchDiscourse();
  for(String noun:List.of("lampe","valise","barque","serrure","tasse")){
   String text="La "+noun+" est rouge. Elle est fragile.";var doc=reader.analyze(text);
   check(doc.sentences().size()==2,"Segmentation française");
   check(doc.references().size()==1&&doc.references().getFirst().status().equals("UNIQUE_AGREEMENT_CANDIDATE"),"Accord unique proposé indépendamment du nom");
   check(reader.read(doc.json()).equals(doc),"JSON réellement relu et contrôlé contre la source");
   for(var sentence:doc.sentences())for(var t:sentence.tokens()){
    check(text.substring(t.start(),t.end()).equals(t.surface()),"Positions exactes des tokens");
    check(Double.isFinite(t.score())&&t.score()>=0&&t.score()<=1,"Score POS borné, pas une probabilité de vérité");
   }
   var summary=ExtractiveTextSummary.summarize(doc);
   check(summary.selected().equals(List.of(0,1)),"Antécédent conservé avec le pronom");
   for(String line:summary.text().lines().toList())check(text.contains(line),"Résumé composé exclusivement d'extraits originaux");
  }
  for(String sentence:List.of("La lampe et la valise sont rouges. Elle est fragile.","La lampe est rouge. La valise est bleue. Elle est fragile.","Nora retrouve une lettre. Elle sourit.")){
   var d=reader.analyze(sentence);
   check(d.references().getFirst().status().equals("AMBIGUOUS"),"Aucun choix par proximité ou prénom");
   check(!ExtractiveTextSummary.summarize(d).text().contains("Elle"),"Pas de pronom extrait sans référence admissible");
  }
  var repeated=reader.analyze("La lampe l9 est rouge. La lampe l9 est fragile. Elle brille.");
  check(repeated.references().getFirst().candidates().size()==1,"Même identifiant explicite regroupé, pas deux objets inventés");
  var unknownRepeat=reader.analyze("Une lampe est rouge. Une lampe est fragile. Elle brille.");
  check(unknownRepeat.references().getFirst().status().equals("AMBIGUOUS"),"Deux indéfinis ne sont pas fusionnés");
  var missing=reader.analyze("Elle chante.");check(missing.references().getFirst().status().equals("UNRESOLVED"),"Absence d'antécédent explicite");
  var gender=reader.analyze("Le coffre est lourd. Elle est fragile.");check(gender.references().getFirst().candidates().isEmpty(),"Genre incompatible");
  var unknown=reader.analyze("Camille attend. Elle chante.");check(!unknown.references().getFirst().status().equals("UNIQUE_AGREEMENT_CANDIDATE"),"Prénom sans genre déclaré");
  var condition=reader.analyze("Si la lampe est rouge, la pièce est claire. Elle brille.");
  check(condition.references().stream().noneMatch(r->r.status().equals("UNIQUE_AGREEMENT_CANDIDATE")),"Pas de transfert affirmé depuis une portée conditionnelle");
  var doc=reader.analyze("Le facteur apporte un colis.");
  rejected(()->reader.read(doc.json().replace("\"LINGUISTIC_CANDIDATES_ONLY\"","\"PROVED\"")),"Statut épistémique falsifié");
  rejected(()->reader.read(doc.json().replace("\"pos\":\"NOUN\"","\"pos\":\"VERB\"")),"Catégorie grammaticale falsifiée");
  rejected(()->reader.read(doc.json().replace("\"end\":2","\"end\":3")),"Positions falsifiées");
  rejected(()->reader.analyze("mot ".repeat(2100)),"Budget caractères");
  rejected(()->reader.analyze("mot ".repeat(300)+"."),"Budget tokens par phrase");
  var scope=reader.analyze("Le silence est une porte que personne ne ferme.");
  check(!scope.clauses().getFirst().limitations().isEmpty(),"Relative explicitement partielle");
  var sequence=reader.analyze("Le train arrive. Une voyageuse attend le train. Le train repart.");
  var summary=ExtractiveTextSummary.summarize(sequence);
  check(summary.selected().size()<=2&&summary.selected().equals(summary.selected().stream().sorted().toList()),"Sélection bornée dans l'ordre original");
  check(summary.selected().size()+summary.omitted().size()==3,"Toute omission déclarée");
  var cycle=new IntegratedQuestionCycle();int[] web={0};
  var router=new FrenchReasoningRouter(cycle,q->{web[0]++;return List.of();});
  var a=router.respond("Analyse ce texte : « La lampe l9 est allumée. Elle éclaire la pièce. »").execution().orElseThrow();
  check(a.discourse().isPresent()&&a.analysis().isPresent(),"Lecteur linguistique et cœur formel accessibles ensemble");
  check(a.analysis().orElseThrow().inferences().isEmpty(),"Aucune inférence créée depuis les seules annotations NLP");
  check(a.analysis().orElseThrow().document().sourceText().equals(a.discourse().orElseThrow().source()),"Même texte source, pas de réécriture cachée");
  var r=router.respond("Résume ce texte : « Une voyageuse attend le train. Elle observe le quai. Le train arrive. »").execution().orElseThrow();
  check(r.status()==TextTaskService.Status.EXTRACTED_SUMMARY&&r.discourse().isPresent(),"Résumé routé depuis une demande naturelle");
  check(cycle.questionActCount()==0&&cycle.premises().isEmpty(),"Aucune faille Q ni prémisse globale NLP");
  int before=web[0];router.respond("Résume ce texte : « Une abeille explore une fleur. »");
  check(web[0]==before,"Résumé sans recherche Web externe");
  check(router.respond("Traduis ce texte : « Une abeille explore une fleur. »").execution().orElseThrow().status()==TextTaskService.Status.UNSUPPORTED_TASK,"Traduction non prétendue");
  check(router.respond("Écris une phrase poétique sur la mer.").execution().orElseThrow().status()==TextTaskService.Status.CREATED,"Création existante préservée");
  check(router.respond("Le module m8 est actif. Est-ce que le module m8 est actif ?").logical().isPresent(),"Cycle logique préservé");
  System.out.println("TextContext0638Test OK : "+checks+" contrôles ; annotations statistiques distinctes des preuves");
 }
}
