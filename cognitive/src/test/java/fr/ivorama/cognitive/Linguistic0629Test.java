package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.FrenchAgreement.*;
import static fr.ivorama.cognitive.StructuredReasoningService.Status.*;

/** Contrôles de mécanismes, paraphrases et contre-exemples ; aucun entraînement par réponse. */
public final class Linguistic0629Test {
 private static int checks;
 private static void ok(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 private static void reject(Runnable action,String reason){try{action.run();throw new AssertionError(reason);}catch(IllegalArgumentException expected){checks++;}}
 private static FrenchStructuredReasoningService.Turn run(String s){return new FrenchStructuredReasoningService(new IntegratedQuestionCycle()).respond(s);}
 private static void morphology(){
  for(String base:List.of("donné","remis","transmis","reçu","pris","ramassé","récupéré","perdu","confié")){
   var p=paradigm(base);var features=List.of(new Features(Gender.MASCULINE,GrammaticalNumber.SINGULAR),new Features(Gender.FEMININE,GrammaticalNumber.SINGULAR),new Features(Gender.MASCULINE,GrammaticalNumber.PLURAL),new Features(Gender.FEMININE,GrammaticalNumber.PLURAL));
   var forms=List.of(p.masculineSingular(),p.feminineSingular(),p.masculinePlural(),p.femininePlural());
   for(int i=0;i<forms.size();i++){ok(p.analyze(forms.get(i)).contains(features.get(i)),"Analyse morphologique non destructive "+forms.get(i));ok(agree(p,forms.get(i),features.get(i)).equals(features.get(i)),"Unification des traits "+forms.get(i));}
   reject(()->agree(p,p.feminineSingular(),new Features(Gender.MASCULINE,GrammaticalNumber.SINGULAR)),"Accord de genre invalide accepté");
  }
  reject(()->new Features(Gender.FEMININE,GrammaticalNumber.SINGULAR).unify(new Features(Gender.MASCULINE,GrammaticalNumber.SINGULAR)),"Traits contradictoires");
  var explicit=new Paradigm("écrit","écrite","écrits","écrites");ok(agree(explicit,"écrites",new Features(Gender.FEMININE,GrammaticalNumber.PLURAL)).number()==GrammaticalNumber.PLURAL,"Paradigme irrégulier déclarable");
  // Combinaisons sans ajout de branches : trois classes d'action, plusieurs genres/nombres.
  for(String base:List.of("donné","remis","transmis","reçu","pris","ramassé","récupéré","perdu"))for(int kind=0;kind<3;kind++){
   var p=paradigm(base);String noun=kind==0?"ballon":kind==1?"enveloppe":"documents";
   String initial=kind==0?"un ballon":kind==1?"une enveloppe":"des documents";
   String object=kind==0?"le ballon":kind==1?"l'enveloppe":"les documents";
   String form=kind==0?p.masculineSingular():kind==1?p.feminineSingular():p.masculinePlural();
   String clitic=kind==2?"les a ":"l'a ";boolean release=base.equals("perdu"),receive=base.equals("reçu");
   String actor=release?"Aline":"Boris";String tail=List.of("donné","remis","transmis").contains(base)?" à Boris":receive?" de Aline":"";
   if(!release&&!receive&&!tail.isEmpty())actor="Aline";
   String text="Aline a ramassé "+initial+". Puis "+actor+" "+clitic+form+tail+". "+(release?"Aline":"Boris")+" détient "+object+" ?";
   var t=run(text);ok(t.translation().complete(),"Accords et rôles compositionnels "+text);ok(t.answer().status()==(release?FALSE_CONDITIONALLY:TRUE_CONDITIONALLY),"Effet indépendant de la flexion "+noun);
  }
  String regression="Emma a ramassé une écharpe. Puis Emma l'a donnée à Lucas. Qui a l'écharpe maintenant ?";
  ok(run(regression).selected().equals(Optional.of("lucas")),"Ancien échec devient test de régression, pas examen aveugle");
  for(String text:List.of(
   "Aline a ramassé un ballon. Puis Aline l'a donnée à Boris. Qui a le ballon ?",
   "Aline a ramassé une enveloppe. Puis Aline l'a donné à Boris. Qui a l'enveloppe ?",
   "Aline a ramassé des documents. Puis Aline l'a donné à Boris. Qui a les documents ?",
   "Aline a ramassé une enveloppe. Puis Aline a donnée l'enveloppe à Boris. Qui a l'enveloppe ?",
   "Aline a ramassé une enveloppe. Puis Aline l'a donnée à Boris. Qui a le enveloppe ?"))ok(run(text).answer().status()==INCOMPLETE_INTERPRETATION,"Accord fautif non toléré par simple retrait de suffixe");
 }
 private static void aspect(){
  String loss="Le facteur a remis le colis à Clara. Puis Clara a perdu le colis. Clara a-t-elle encore le colis ?";
  var t=run(loss);ok(t.answer().status()==FALSE_CONDITIONALLY&&t.answer().explanation().contains("Présupposition d'encore vérifiée"),"Encore conserve sa présupposition et répond non après perte");
  var n=EventNarrative.read(t.narrativeJson().orElseThrow());ok(n.query().aspect()==EventNarrative.Aspect.STILL,"Aspect conservé dans le vrai JSON");
  ok(EventNarrative.read(EventNarrative.write(n)).equals(n),"Aller-retour schéma 2");
  var simple=new FrenchEventTranslator().translate("Aline a le ballon. Aline a-t-elle le ballon ?");ok(simple.json().contains("ivoc.events.1")&&EventNarrative.read(simple.json()).query().aspect()==EventNarrative.Aspect.CURRENT,"Schéma 1 maintenu");
  var unknown=run("Aline a ramassé le ballon. Boris a-t-il encore le ballon ?");ok(unknown.answer().status()==INCOMPLETE_INTERPRETATION && unknown.answer().explanation().contains("non établie"),"Passé présupposé inconnu : pas d'invention");
  var contradicted=run("Aline n'a pas le ballon. Aline a ramassé le ballon. Aline a-t-elle encore le ballon ?");ok(contradicted.answer().status()==INCOMPLETE_INTERPRETATION && contradicted.answer().explanation().contains("contredite"),"Présupposition fausse : pas d'aplatissement en état courant vrai");
  var retained=run("Aline a le ballon. Boris a ramassé le carnet. Aline a-t-elle encore le ballon ?");ok(retained.answer().status()==TRUE_CONDITIONALLY,"Inertie et état précédent établis");
  for(String text:List.of("Aline a le ballon. Aline a-t-elle encore le ballon ?","Aline a le ballon. Qui a encore le ballon ?","Aline a le ballon. Aline a-t-elle forcément encore le ballon ?"))ok(run(text).answer().status()==INCOMPLETE_INTERPRETATION,"Pas de choix de portée ou d'historique fictif");
  var cycle=new IntegratedQuestionCycle();var service=new FrenchStructuredReasoningService(cycle);var first=service.respond(loss);var again=service.revisit(first.answer().questionId());ok(again.answer().status()==first.answer().status()&&cycle.questionActCount()==1,"Révision inclut présupposition sous même Q");
 }
 private static void time(){
  ok(FrenchRelativeTime.seconds("vingt","minutes")==1200,"Cardinal et unité exacts");
  ok(FrenchRelativeTime.seconds("soixante et onze","secondes")==71,"Composition 71");
  ok(FrenchRelativeTime.seconds("quatre-vingts","secondes")==80,"80 avec s");
  ok(FrenchRelativeTime.seconds("quatre-vingt-dix-neuf","secondes")==99,"Composition 99");
  ok(FrenchRelativeTime.seconds("deux cents","secondes")==200,"Centaines exactes");
  ok(FrenchRelativeTime.seconds("neuf cent quatre-vingt-dix-neuf","secondes")==999,"Borne cardinal");
  ok(FrenchRelativeTime.seconds("2","heures")==7200,"Numéral et conversion");
  reject(()->FrenchRelativeTime.seconds("10000000","jours"),"Budget durée");reject(()->FrenchRelativeTime.seconds("quelques","minutes"),"Quantité approximative");reject(()->FrenchRelativeTime.seconds("deux mille","secondes"),"Cardinal hors fragment");
  String text="Aline a ramassé le carnet il y a vingt minutes. Aline a donné le carnet à Boris il y a cinq minutes. Qui détient le carnet maintenant ?";
  var t=run(text);ok(t.selected().equals(Optional.of("boris")),"Repères datés remplacent ordre lexical absent");var n=EventNarrative.read(t.narrativeJson().orElseThrow());
  ok(n.events().get(0).secondsBeforeNow().orElseThrow()==1200 && n.events().get(1).secondsBeforeNow().orElseThrow()==300,"Repères exacts, pas éliminés de la représentation");
  ok(EventNarrative.read(EventNarrative.write(n)).equals(n),"Repères JSON relus sans perte");
  ok(t.translation().problem().orElseThrow().premises().stream().anyMatch(p->p.evidence().excerpt().contains("1200 secondes")),"Repère exposé dans provenance des contraintes");
  for(String bad:List.of(
   "Aline a ramassé le carnet il y a cinq minutes. Puis Aline a donné le carnet à Boris il y a vingt minutes. Qui a le carnet ?",
   "Aline a ramassé le carnet il y a quelques minutes. Qui a le carnet ?",
   "Aline a ramassé le carnet pendant vingt minutes. Qui a le carnet ?",
   "Aline a ramassé le carnet il y a 99999999 jours. Qui a le carnet ?"))ok(run(bad).answer().status()==INCOMPLETE_INTERPRETATION,"Repère incohérent ou inconnu bloque tout calcul");
  String cooking="J'ai mis mon gâteau au four il y a vingt minutes. Est-ce qu'il est forcément cuit ?";ok(run(cooking).answer().status()==INCOMPLETE_INTERPRETATION,"Pas de recette causale inventée à partir d'une durée");
  reject(()->EventNarrative.read(t.narrativeJson().orElseThrow().replace("1200","-1200")),"Repère négatif JSON");
 }
 public static void main(String[] args){morphology();aspect();time();System.out.println("LINGUISTIC-0629 OK - "+checks+" contrôles");}
}
