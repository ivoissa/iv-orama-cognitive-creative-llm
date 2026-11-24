package fr.ivorama.cognitive;
import java.util.*;

/** Exposed contract tests; real OpenNLP/Jena dependencies, offline document provider. */
public final class Corrections0639Test {
 private static int checks;
 private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 private static void rejected(Runnable test,String message){boolean rejected=false;try{test.run();}catch(IllegalArgumentException ex){rejected=true;}check(rejected,message);}
 private static void limits(){
  var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,q->{throw new AssertionError("Aucun réseau pour une entrée hors budget");});
  for(String start:List.of("Écris un poème sur ","Analyse ce texte : ","Explique ce passage : ","Résume ce texte : ","Pourrais-tu composer un poème sur "))for(int n:List.of(8001,12000,100000)){
   String source=start+"x".repeat(n-start.length());var d=DirectiveActs.classify(source).orElseThrow();
   check(d.sourceText().equals(source),"Source intégrale conservée en mémoire, diagnostic seul tronqué");
   var r=router.respond(source).execution().orElseThrow();check(r.status()==TextTaskService.Status.RESOURCE_LIMIT,"Limite avant relecture et tâche");
   check(r.requestJson().length()<1000&&r.requestJson().contains("truncated"),"Diagnostic borné et troncature explicite");
   check(new TextTaskService().execute(d).status()==TextTaskService.Status.RESOURCE_LIMIT,"Même garde pour appel direct");
  }
  check(cycle.questionActCount()==0&&cycle.premises().isEmpty(),"Aucun QUESTION_POSED ni fait global hors budget");
  String valid="Écris une phrase poétique sur la mer.";valid+=" ".repeat(8000-valid.length());
  check(router.respond(valid).execution().orElseThrow().status()==TextTaskService.Status.CREATED,"Frontière 8000 admise");
 }
 private static void requests(){
  var themes=CreativeRequest.parse("un poème sur la mer et la forêt");check(themes.themes().equals(List.of("mer","forêt"))&&themes.unresolved().isEmpty(),"Conjonction de thèmes, pas résidus par mot");
  check(CreativeRequest.read(themes.json()).equals(themes),"Thèmes relus depuis JSON");
  for(String modifier:List.of("joli","magnifique","long","petit joli")){
   var request=CreativeRequest.parse("un "+modifier+" poème sur la mer");check(request.form().equals("POEM")&&request.unresolved().stream().anyMatch(s->s.contains(modifier)),"Modificateur nommé sans interprétation silencieuse");
  }
  for(String wrong:List.of("un phrase","une poème","une texte","un histoire","une récit"))check(!CreativeRequest.parse(wrong).unsupported().isEmpty(),"Accord du déterminant conservé après généralisation du format");
  var story=CreativeRequest.parse("une histoire sur la mer");check(story.form().equals("STORY")&&story.unsupported().stream().anyMatch(s->s.contains("narrative")),"Format reconnu, capacité narrative non inventée");
  var tone=CreativeRequest.parse("un texte sur la mer avec un ton mélancolique");check(tone.unresolved().equals(List.of("avec un ton mélancolique")),"Résidu groupé");
  var engine=new CreativeTextEngine();
  try{engine.compose(CreativeTextEngine.Style.PLAIN,Optional.of("mer"),4,1);throw new AssertionError("Capacité insuffisante attendue");}
  catch(CreativeTextEngine.CompositionUnavailable ex){check(ex.getMessage().contains("2 constructions")&&ex.getMessage().contains("4 lignes"),"Capacité chiffrée");}
  var varying=engine.compose(CreativeTextEngine.Style.POETIC,Optional.of("mer"),4,32);
  check(varying.plan().json().contains("ivoc.content-plan.2"),"Version du contrat explicite pour sujets variés");
  rejected(()->CreativeTextEngine.readPlan(varying.plan().json().replace("ivoc.content-plan.2","ivoc.content-plan.1"),engine.vocabulary()),"Ancien invariant v1 préservé");
  var one=engine.compose(CreativeTextEngine.Style.POETIC,Optional.of("mer"),1,32);
  check(one.plan().json().contains("ivoc.content-plan.1")&&CreativeTextEngine.readPlan(one.plan().json(),engine.vocabulary()).equals(one.plan()),"Ancien contrat monoligne toujours lisible");
  rejected(()->CreativeTextEngine.readPlan(one.plan().json().replace("\"ivoc.content-plan.1\"","null"),engine.vocabulary()),"Schéma nul rejeté explicitement sans panne interne");
  for(var noun:engine.vocabulary().nouns())for(int seed=0;seed<8;seed++){
   var c=engine.compose(CreativeTextEngine.Style.POETIC,Optional.of(noun.word()),4,seed);var clauses=c.plan().clauses();
   check(clauses.getFirst().subject().equals(noun.id())&&c.text().contains(noun.word()),"Ancrage initial du thème");
   check(clauses.stream().map(CreativeTextEngine.Clause::verb).distinct().count()==2,"Deux verbes déclarés réellement utilisés");
   for(int i=1;i<clauses.size();i++)check(!clauses.get(i-1).verb().equals(clauses.get(i).verb()),"Pas de répétition verbale adjacente si alternative disponible");
   boolean siblings=engine.vocabulary().nouns().stream().anyMatch(n->!n.id().equals(noun.id())&&!Collections.disjoint(n.tags(),noun.tags()));
   if(siblings)check(clauses.stream().map(CreativeTextEngine.Clause::subject).distinct().count()>1,"Variation du sujet dans le champ lexical déclaré");
   check(FrenchTextRealizer.read(c.text(),engine.vocabulary()).equals(clauses),"Cohérence interne conservée, pas test de compréhension externe");
  }
 }
 private static void proofs(){
  var analyzer=new TextContextAnalysis(CreativeTextEngine.defaults());
  for(String noun:List.of("relais r4","module m9","signal s8","capteur c6")){
   String entity="Le "+noun;String rule=" Si "+entity.toLowerCase(Locale.ROOT)+" est actif, alors "+entity.toLowerCase(Locale.ROOT)+" est disponible.";
   var direct=analyzer.analyze(entity+" est actif."+rule);
   check(direct.inferences().stream().anyMatch(i->i.proofChecked()&&i.reasoning().contains("modus ponens")),"Explication depuis le vrai terme de preuve");
   var reverse=analyzer.analyze(entity+" n'est pas disponible."+rule);
   check(reverse.inferences().stream().anyMatch(i->i.proofChecked()&&i.reasoning().contains("modus tollens (contraposée)")),"Contraposée nommée après vérification");
   check(reverse.describe().contains("Source txt_p0")&&reverse.describe().contains("n'est pas disponible"),"Sources textuelles présentées");
   check(!reverse.describe().contains("négation de (le"),"Présentation naturelle, ancien champ machine non affiché");
   check(analyzer.analyze(rule.strip()).inferences().isEmpty(),"Aucune explication réutilisée après retrait du fait");
  }
  var p=Formula.atom("p");rejected(()->FrenchProofExplanation.renderChecked(p,new Proof.Assumption(p),List.of(),f->"texte"),"Une présentation ne certifie pas une preuve invalide");
 }
 private static void readers(){
  var registry=new TextReadingRegistry();
  for(String person:List.of("Lina","Paul","Élise")){
   String text=person+" a pris une clé. Le capteur c3 est actif.";var r=registry.analyze(text);
   check(r.complete()&&r.readings().stream().anyMatch(x->x.reader().equals("EVENT")),"Lecture événementielle sans fabriquer de question");
   check(r.premises().stream().anyMatch(p->p.evidence().origin()==StructuredLogic.Origin.DOMAIN_RULE),"Rôles compilés avec schémas déclarés");
   check(registry.read(r.json()).premises().equals(r.premises()),"JSON des contraintes relu et décodé");
   var result=new TextContextAnalysis(CreativeTextEngine.defaults()).analyze(text);check(result.formallyComplete()&&result.status().equals("STRUCTURE_RECOGNIZED"),"Texte mixte couvert par les lecteurs formels");
   rejected(()->registry.read(r.json().replace("\"origin\":\"DOMAIN_RULE\"","\"origin\":\"DECLARATION\"")),"Provenance de schéma falsifiée rejetée");
  }
  var sequence=registry.analyze("Lina a pris une clé. Puis Lina a perdu la clé.");check(sequence.complete(),"Ordre explicite des événements");
  var unordered=registry.analyze("Lina a pris une clé. Lina a perdu la clé.");check(!unordered.complete(),"Ordre temporel non deviné");
  var reversedTime=registry.analyze("Lina a pris une clé il y a deux heures. Puis Lina a perdu la clé il y a trois heures.");check(!reversedTime.complete(),"Chronologie contradictoire refusée même avec puis");
  var noOtherDomain=registry.analyze("Le document d1 est valide. Tous les dossiers sont valides.");check(!noOtherDomain.complete(),"Un autre domaine déclaré ne remplace pas celui du quantificateur");
  var selfTransfer=registry.analyze("Lina a donné une clé à Lina.");check(!selfTransfer.complete(),"Conflit de rôles du schéma signalé sans conclusion ni panne interne");
  var outside=registry.analyze("Élise a quitté la gare.");check(!outside.complete(),"Aucun faux support du déplacement hors lexique événementiel");
  String quantified="Le dossier d1 est complet. Le dossier d2 est signe. Tous les dossiers sont valides. Si le dossier d1 est valide, alors le dossier d1 est stable.";
  var quant=registry.analyze(quantified);check(quant.complete()&&quant.readings().stream().anyMatch(r->r.reader().equals("FINITE_QUANTIFIER")),"Lecteur de quantificateurs finis appelé");
  var result=new TextContextAnalysis(CreativeTextEngine.defaults()).analyze(quantified);
  check(result.inferences().stream().anyMatch(i->i.proofChecked()&&i.explanation().contains("stable")&&i.premiseIds().stream().anyMatch(id->id.startsWith("quant_"))),"Prémisse quantifiée réinjectée dans la preuve, provenance conservée");
  check(!registry.analyze("Tous les dossiers sont valides.").complete(),"Pas de domaine présumé ou de vacuité inventée");
  check(!registry.analyze("Tous les capteurs sont stables.").complete(),"Quantification libre non revendiquée");
  var partial=new TextContextAnalysis(CreativeTextEngine.defaults()).analyze(quantified+" Le dragon traverse le miroir.");
  check(!partial.formallyComplete()&&partial.inferences().isEmpty(),"Aucune déduction globale après lecture partielle");
  var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,q->List.of());
  router.respond("Analyse ce texte : « Lina a pris une clé. Le capteur c3 est actif. »");
  check(cycle.questionActCount()==0&&cycle.premises().isEmpty(),"Pont documentaire local, sans fausse Q ni faits globaux");
 }
 public static void main(String[] args){limits();requests();proofs();readers();System.out.println("Corrections0639Test OK : "+checks+" contrôles");}
}
