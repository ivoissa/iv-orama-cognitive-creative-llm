package fr.ivorama.cognitive;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static fr.ivorama.cognitive.Formula.*;

/** Audit de développement, jamais un reclassement automatique du score DEV-100. */
public final class DevelopmentGapAudit {
 public enum Layer { BANC_INTERFACE, LANGUE, LOGIQUE, EMPIRIQUE, MIXTE }
 public enum Scope { API, COMPONENT, LIMIT, UNVERIFIED }
 public record Entry(String id,Layer layer,Scope scope,String probe,String limitation,String nextAction){}
 public record Trace(String id,String probe,Layer layer,Scope scope,String execution,List<String> checks,Map<String,String> observations,String error){
  public Trace {checks=List.copyOf(checks);observations=Map.copyOf(observations);}
 }
 public record Report(String catalogHash,String activationHash,String auditHash,String legacySummary,List<Trace> cases){public Report{cases=List.copyOf(cases);}}
 private static final Set<String> PROBES=Set.of("XOR","CLASSICAL_TAUTOLOGY","PROOF_CANDIDATES","DOUBLE_NEGATION","BUDGET_CHAIN","SOURCE_CONFLICT","OBSERVATION_EXPIRY","OBSERVATION_REVOCATION","NO_EMPIRICAL_CERTIFICATION","EMPTY_DOMAIN","BUDGET_BRANCH","EXCLUSIVE_PARTITIONS","LEXICON_GUARD","ATOMIC_REPLACE","MULTI_QUESTION","FRENCH_FRAGMENT","RELATION_ROLES","NEW_DOMAIN","SECOND_ORDER_REJECTION","NONE");
 private static final class Run {
  final List<String> checks=new ArrayList<>();final Map<String,String> data=new LinkedHashMap<>();
  void require(boolean value,String label){if(!value)throw new AssertionError(label);checks.add(label);}
  void record(String key,Object value){data.put(key,String.valueOf(value));}
  void rejects(Class<? extends RuntimeException> type,Runnable action,String label){
   try{action.run();}catch(RuntimeException ex){if(!type.isInstance(ex))throw ex;record(label,ex.getClass().getSimpleName()+": "+ex.getMessage());checks.add(label);return;}
   throw new AssertionError(label+": aucun rejet");
  }
 }
 public static List<Entry> readEntries(Path manifest,Set<String> expected)throws Exception {
  var lines=Files.readAllLines(manifest,StandardCharsets.UTF_8);
  if(lines.isEmpty()||!lines.getFirst().equals("id\tlayer\tscope\tprobe\tlimitation\tnext_action"))throw new IllegalArgumentException("En-tête audit invalide");
  var entries=new ArrayList<Entry>();var ids=new HashSet<String>();
  for(String line:lines.subList(1,lines.size())){
   if(line.isBlank())continue;String[] f=line.split("\t",-1);
   if(f.length!=6||Arrays.stream(f).anyMatch(String::isBlank)||!ids.add(f[0])||!PROBES.contains(f[3]))throw new IllegalArgumentException("Ligne audit invalide : "+line);
   var entry=new Entry(f[0],Layer.valueOf(f[1]),Scope.valueOf(f[2]),f[3],f[4],f[5]);
   Scope verifiedScope=switch(entry.probe()){
    case "OBSERVATION_EXPIRY","OBSERVATION_REVOCATION","BUDGET_BRANCH","ATOMIC_REPLACE","MULTI_QUESTION"->Scope.API;
    case "EMPTY_DOMAIN","FRENCH_FRAGMENT","SECOND_ORDER_REJECTION"->Scope.LIMIT;
    case "NONE"->Scope.UNVERIFIED;
    default->Scope.COMPONENT;
   };
   if(entry.scope()!=verifiedScope)throw new IllegalArgumentException("Portée non démontrée par le probe : "+entry.id());
   entries.add(entry);
  }
  if(!ids.equals(expected))throw new IllegalArgumentException("Les cas de l'audit diffèrent des lacunes DEV-100 : "+ids+" / "+expected);
  return List.copyOf(entries);
 }
 private static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
 public static Report run(Path catalog,Path manifest)throws Exception {
  var legacy=Development100.evaluate(catalog);Set<String> expected=new LinkedHashSet<>();
  legacy.cases().stream().filter(c->c.scope().equals("NON_PRIS_EN_CHARGE")).forEach(c->expected.add(c.id()));
  var entries=readEntries(manifest,expected);var questions=new LinkedHashMap<String,String>();var premises=new LinkedHashMap<String,String>();
  for(String line:Files.readAllLines(catalog).subList(1,Files.readAllLines(catalog).size()))if(!line.isBlank()){
   String[] f=line.split("\t",-1);questions.put(f[0],f[5]);premises.put(f[0],f[4]);
  }
  var traces=new ArrayList<Trace>();
  for(var entry:entries){
   var r=new Run();String execution="NOT_RUN",error="";
   if(!entry.probe().equals("NONE"))try{
    exercise(entry,r,questions.get(entry.id()),premises.get(entry.id()));
    if(r.checks.isEmpty())throw new AssertionError("Aucun contrôle effectif");execution="PASSED";
   }catch(RuntimeException|AssertionError ex){execution="FAILED";error=ex.getClass().getSimpleName()+": "+ex.getMessage();}
   traces.add(new Trace(entry.id(),entry.probe(),entry.layer(),entry.scope(),execution,r.checks,r.data,error));
  }
  if(!hash(catalog).equals(legacy.sourceHash()))throw new IllegalStateException("Catalogue modifié pendant audit");
  return new Report(legacy.sourceHash(),legacy.activationHash(),hash(manifest),legacy.summary(),traces);
 }
 private static void exercise(Entry e,Run r,String question,String premises){
  Formula p=atom("p"),q=atom("q");var budget=ModelExplorer.Budget.small();
  switch(e.probe()){
   case "XOR"->{
    Formula xor=and(not(and(p,q)),not(and(not(p),not(q))));
    var a=ModelExplorer.analyze(List.of(xor,p),q,budget);r.record("modelStatus",a.status());r.require(a.status()==ModelExplorer.Status.PROVEN_FALSE,"exactement un + premier vrai => second faux");
    var unknown=ModelExplorer.analyze(List.of(xor),q,budget);r.require(unknown.status()==ModelExplorer.Status.UNDETERMINED,"sans premier vrai, second indéterminé");
    int worlds=0;for(int mask=0;mask<4;mask++){boolean x=(mask&1)!=0,y=(mask&2)!=0;r.require(xor.evaluate(Map.of("p",x,"q",y))==(x!=y),"oracle XOR valuation "+mask);if(x!=y)worlds++;}r.record("xorWorlds",worlds);
   }
   case "CLASSICAL_TAUTOLOGY"->{
    Formula classicalOr=not(and(not(p),not(not(p))));var a=ModelExplorer.analyze(List.of(),classicalOr,budget);
    r.record("classicalValidity",a.status());r.require(a.status()==ModelExplorer.Status.PROVEN_TRUE,"validité classique de l'encodage de p ou non p");
    r.require(ModelExplorer.analyze(List.of(),p,budget).status()==ModelExplorer.Status.UNDETERMINED,"une formule arbitraire n'est pas un théorème");r.record("syntacticProofClaimed",false);
   }
   case "PROOF_CANDIDATES"->{
    var good=ProofChecker.verify(new Proof.ModusPonens(new Proof.Assumption(p),new Proof.Assumption(implies(p,q))),Set.of(p,implies(p,q)));
    var bad=ProofChecker.verify(new Proof.Assumption(not(q)),Set.of(p,implies(p,q)));
    r.require(good.valid()&&good.conclusion().equals(q),"preuve fournie valide avec ses hypothèses");r.require(!bad.valid(),"prétention opposée sans hypothèse refusée");r.record("candidateA",good.explanation());r.record("candidateB",bad.explanation());
   }
   case "DOUBLE_NEGATION"->{
    r.require(ModelExplorer.analyze(List.of(not(not(p))),p,budget).status()==ModelExplorer.Status.PROVEN_TRUE,"double négation sur modèles classiques");
    r.require(ModelExplorer.analyze(List.of(),p,budget).status()==ModelExplorer.Status.UNDETERMINED,"sans prémisse aucune affirmation");
    r.require(!new FrenchStructuredTranslator().translate(question).complete(),"forme française du catalogue non traduite complètement");
   }
   case "BUDGET_CHAIN"->{
    List<Formula> chain=new ArrayList<>();chain.add(atom("n0"));for(int i=0;i<500;i++)chain.add(implies(atom("n"+i),atom("n"+(i+1))));
    var a=ModelExplorer.analyze(chain,atom("n500"),new ModelExplorer.Budget(20,30));r.record("status",a.status());r.record("budgetUnit","maxAssignments=30; maxAtoms=20");
    r.require(a.status()==ModelExplorer.Status.RESOURCE_LIMIT,"limite stricte et non faux par défaut");r.require(a.trueWitness().isEmpty()&&a.falseWitness().isEmpty(),"aucun témoin fictif sous limite");
   }
   case "SOURCE_CONFLICT"->{
    var c=new IntegratedQuestionCycle();c.teach("p","source-A déclarée");c.teach("non p","source-B déclarée");var a=c.ask("p");
    r.require(a.status()==IntegratedQuestionCycle.Status.INCONSISTENT,"désaccord détecté sans arbitrage inventé");r.require(c.premises().stream().map(KnowledgeBase.Premise::source).distinct().count()==2,"provenances distinctes conservées");r.record("status",a.status());
   }
   case "OBSERVATION_EXPIRY","OBSERVATION_REVOCATION","NO_EMPIRICAL_CERTIFICATION"->observations(e.probe(),r);
   case "EMPTY_DOMAIN"->{
    var d=new SemanticIntermediateRepresentation.EntityRef("item","a");
    var predicate=new StructuredLogic.Predicate("s",List.of("item"));
    var goal=new StructuredLogic.ForAll("x","item",new StructuredLogic.Atom("s",List.of(new StructuredLogic.Variable("x"))));
    var problem=new StructuredLogic.Problem("Domaine vide explicitement demandé",List.of(),List.of(predicate),List.of(),goal,List.of(),List.of());
    var service=new StructuredReasoningService(new IntegratedQuestionCycle());
    var answer=service.askJson(StructuredJsonWriter.write(problem));r.record("status",answer.status());r.record("reason",answer.explanation());r.require(answer.status()==StructuredReasoningService.Status.INVALID_INPUT,"domaine vide rejeté ; aucune vacuité prétendue");
    var nonempty=new StructuredLogic.Problem("Contrôle domaine non vide",List.of(d),List.of(predicate),List.of(),goal,List.of(),List.of());
    r.require(service.askJson(StructuredJsonWriter.write(nonempty)).status()==StructuredReasoningService.Status.UNDETERMINED,"contrôle positif du même schéma non vide");
   }
   case "BUDGET_BRANCH"->{
    var a=ModelExplorer.analyzeFull(List.of(),and(p,q),new ModelExplorer.Budget(2,1));r.record("status",a.status());
    r.require(a.status()==ModelExplorer.Status.RESOURCE_LIMIT,"une affectation ne permet pas l'énumération complète");r.require(a.possibleWorlds().isEmpty()&&!a.completeWorlds(),"aucune énumération partielle publiée comme complète");
    r.require(ModelExplorer.analyzeFull(List.of(),and(p,q),new ModelExplorer.Budget(2,4)).status()==ModelExplorer.Status.UNDETERMINED,"budget suffisant distingue indétermination et limite");
   }
   case "EXCLUSIVE_PARTITIONS"->{
    Formula a=and(p,not(q)),b=and(not(p),q);var def=new QuestionPartition.Definition(List.of(new QuestionPartition.Cell("A_seul",a),new QuestionPartition.Cell("B_seul",b),new QuestionPartition.Cell("autre",and(not(a),not(b)))));
    r.require(QuestionPartition.valid(def,budget),"partition disjointe exhaustive");
    var chosen=QuestionPartition.evaluate(def,List.of(p,not(q)),budget);r.record("selected",chosen.selected().orElseThrow().name());r.require(chosen.selected().orElseThrow().name().equals("A_seul"),"une seule cellule sélectionnée");
    r.require(ModelExplorer.analyze(List.of(a,b),p,budget).status()==ModelExplorer.Status.INCONSISTENT,"les deux réponses exclusives ne coexistent pas");
    var invalid=new QuestionPartition.Definition(List.of(new QuestionPartition.Cell("a",p),new QuestionPartition.Cell("b",q)));r.require(!QuestionPartition.valid(invalid,budget),"cellules se chevauchant refusées");
   }
   case "LEXICON_GUARD"->{
    var d=new FrenchQuestionDialogue();d.declare("document","a");d.registerAdjective("sens1",Set.of("document"),"sobre","sobre","sobres","sobres");d.registerAmbiguousAdjective("sens2",Set.of("document"),"sobre","sobre","sobres","sobres");
    var opened=d.ask(question);String before=d.lexicalFingerprint();r.require(opened.pending(),"clarification initiale ouverte");
    d.registerAdjective("nouveau",Set.of("document"),"neuf","neuve","neufs","neuves");r.require(!before.equals(d.lexicalFingerprint()),"mutation du lexique détectable");
    r.rejects(IllegalStateException.class,()->d.tryClarify(opened.questionId(),"sens1"),"clarification après mutation refusée");r.require(d.cycle().questionActCount()==1&&d.cycle().premises().isEmpty(),"aucun nouvel acte ni fait fabriqué");r.record("typedDialogueResult",false);
   }
   case "ATOMIC_REPLACE"->{
    var c=new IntegratedQuestionCycle();String id=c.teach("p","v1");var before=c.ask("p");long v=c.knowledgeVersion();var cert=before.certificate().orElseThrow();
    c.replace(id,"non p","correction");var after=c.question(before.id());r.record("before",before.answer());r.record("after",after.answer());
    r.require(after.answer().equals(Optional.of(false)),"remplacement révise la réponse existante");r.require(c.knowledgeVersion()==v+1,"une seule révision atomique");r.require(!c.isCertificateCurrent(before.id(),cert),"ancien certificat invalidé");
    long stable=c.knowledgeVersion();r.rejects(IllegalArgumentException.class,()->c.replace(id,"p",""),"remplacement invalide refusé");r.require(c.knowledgeVersion()==stable&&c.question(before.id()).answer().equals(Optional.of(false)),"échec sans mutation partielle");
   }
   case "MULTI_QUESTION"->{
    var d=new FrenchQuestionDialogue();d.declare("dossier","a");d.teach("Le dossier a est validé.");var first=d.ask("Le dossier a est-il validé ?");var second=d.ask("Pourquoi le dossier a est-il validé ?");
    r.require(first.report().answer().equals(Optional.of(true)),"première question résolue conditionnellement");r.require(!first.questionId().equals(second.questionId())&&d.cycle().questionActCount()==2,"deux actes distincts dans la même session");
    var why=d.cycle().explanationQuestion(second.questionId());r.record("explanationStatus",why.status());r.require(why.status()==IntegratedQuestionCycle.ExplanationStatus.DIRECT_ASSERTION_ONLY,"répéter un fait ne crée pas une explication déductive");
   }
   case "FRENCH_FRAGMENT"->{
    String input=Arrays.stream(premises.split(";")).filter(s->s.startsWith("sais ")).map(s->s.substring(5)).reduce("",(a,b)->a+b+" ")+question;
    var t=new FrenchStructuredTranslator().translate(input);r.record("input",input);r.record("unresolved",t.unresolved());r.require(!t.complete(),"scénario français du catalogue non intégralement traduit");
    r.require(new FrenchStructuredTranslator().translate("Le module m1 est actif. Est-ce que le module m1 est actif ?").complete(),"contrôle positif du traducteur");
   }
   case "RELATION_ROLES","NEW_DOMAIN"->relations(r,e.probe().equals("NEW_DOMAIN"));
   case "SECOND_ORDER_REJECTION"->{
    String good="{\"sourceText\":\"Contrôle syntaxique\",\"entities\":[{\"kind\":\"item\",\"name\":\"a\"}],\"predicates\":[{\"name\":\"p\",\"kinds\":[\"item\"]}],\"premises\":[],\"goal\":{\"atom\":\"p\",\"args\":[\"a\"]}}";
    var service=new StructuredReasoningService(new IntegratedQuestionCycle());r.require(service.askJson(good).status()==StructuredReasoningService.Status.UNDETERMINED,"contrôle JSON propositionnel valide");
    String unknown=good.replace("{\"atom\":\"p\",\"args\":[\"a\"]}","{\"forallPredicate\":\"P\",\"body\":{\"atom\":\"p\",\"args\":[\"a\"]}}");var a=service.askJson(unknown);r.record("status",a.status());r.record("reason",a.explanation());r.require(a.status()==StructuredReasoningService.Status.INVALID_INPUT,"opérateur de second ordre non inventé");
   }
   default->throw new IllegalArgumentException("Probe inconnu : "+e.probe());
  }
 }
 private static void observations(String probe,Run r){
  var now=Instant.parse("2026-10-21T10:00:00Z");var end=now.plusSeconds(60);var c=new IntegratedQuestionCycle();c.teach("b -> c","règle déclarée");c.registerObservable("b",1);var q=c.ask("c");
  r.require(q.status()==IntegratedQuestionCycle.Status.NEED_INFORMATION,"observation demandée avant mesure");
  var reading=c.captureObservation("source déclarée",(Formula.Atom)atom("b"),true,"calibration déclarée",now,end);var answer=c.answerRecordedObservation(q.id(),reading.id(),now.plusSeconds(1));
  r.record("admittedStatus",answer.status());r.record("support",answer.support());r.require(answer.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"mesure admise seulement conditionnellement");r.require(answer.support()!=IntegratedQuestionCycle.Support.INDEPENDENTLY_VERIFIED,"aucune certification physique déduite de l'intégrité");
  if(probe.equals("OBSERVATION_EXPIRY")){
   c.expireRecordedObservations(end);r.record("recordStatus",c.observationStatus(reading.id(),end));r.require(c.observationStatus(reading.id(),end)==ObservationLedger.Status.EXPIRED,"expiration exacte à la borne");
   r.require(c.question(q.id()).reopened()&&c.question(q.id()).answer().isEmpty(),"expiration retire le support et rouvre Q");r.rejects(IllegalStateException.class,()->c.answerRecordedObservation(q.id(),reading.id(),end),"lecture expirée non réadmise");
  }else if(probe.equals("OBSERVATION_REVOCATION")){
   c.revokeRecordedObservation(reading.id(),"mesure contestée");r.require(c.observationStatus(reading.id(),now)==ObservationLedger.Status.REVOKED,"révocation effective");r.require(c.question(q.id()).reopened()&&c.question(q.id()).answer().isEmpty(),"révocation rouvre Q");r.rejects(IllegalStateException.class,()->c.answerRecordedObservation(q.id(),reading.id(),now),"lecture révoquée non réadmise");
  }else{
   var corrupted=new ObservationLedger.Reading(reading.id(),reading.sourceId(),reading.measuredAtom(),false,reading.calibrationId(),reading.capturedAt(),reading.expiresAt(),reading.digest());
   r.require(ObservationLedger.checkDetached(corrupted,now)==ObservationLedger.Status.CORRUPT,"altération détectée sans prétendre vérifier le calibrage");r.record("signatureAuthenticationTested",false);r.record("calibrationIndependentlyVerified",false);
  }
 }
 private static void relations(Run r,boolean domain){
  var a=new SemanticIntermediateRepresentation.EntityRef(domain?"artifact":"entity","a");var b=new SemanticIntermediateRepresentation.EntityRef(domain?"organization":"entity","b");
  var def=new StructuredLogic.Predicate("r",List.of(a.kind(),b.kind()));
  var forward=new StructuredLogic.Atom("r",List.of(new StructuredLogic.Constant(a),new StructuredLogic.Constant(b)));
  var evidence=new StructuredLogic.Evidence(StructuredLogic.Origin.DECLARATION,"fixture","relation explicitement enseignée");
  var premise=new StructuredLogic.Premise("given",forward,evidence);var service=new StructuredReasoningService(new IntegratedQuestionCycle());
  var withFact=new StructuredLogic.Problem("Relation déclarée",List.of(a,b),List.of(def),List.of(premise),forward,List.of(),List.of());
  r.require(service.askJson(StructuredJsonWriter.write(withFact)).status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"relation dirigée enseignée reconnue");
  var absent=new StructuredLogic.Problem("Sans fait de relation",List.of(a,b),List.of(def),List.of(),forward,List.of(),List.of());r.require(service.askJson(StructuredJsonWriter.write(absent)).status()==StructuredReasoningService.Status.UNDETERMINED,"aucune relation devinée sans fait");
  if(!domain){var reversed=new StructuredLogic.Atom("r",List.of(new StructuredLogic.Constant(b),new StructuredLogic.Constant(a)));var test=new StructuredLogic.Problem("Rôles inversés",List.of(a,b),List.of(def),List.of(premise),reversed,List.of(),List.of());var reply=service.askJson(StructuredJsonWriter.write(test));r.record("reversedStatus",reply.status());r.require(reply.status()==StructuredReasoningService.Status.UNDETERMINED,"l'inverse n'est ni affirmé ni nié par le fait initial");}
 }
 public static String markdown(Report report,List<Entry> entries){
  var byId=new HashMap<String,Entry>();entries.forEach(e->byId.put(e.id(),e));
  var b=new StringBuilder("# Audit des 25 lacunes DEV-100 — 0.6.48\n\nScore historique inchangé : ").append(report.legacySummary()).append("\n\nUne réussite ci-dessous valide un contrôle API ou une limite, jamais automatiquement toute la question française. Aucun nouveau point DEV-100. Les probes sont des fixtures de développement exposées, sans LLM ni test réseau.\n\n| Cas | Couche | Portée | Exécution | Limite restante | Étape utile |\n|---|---|---|---|---|---|\n");
  for(var t:report.cases()){var e=byId.get(t.id());b.append("| ").append(t.id()).append(" | ").append(t.layer()).append(" | ").append(t.scope()).append(" | ").append(t.execution()).append(" | ").append(e.limitation()).append(" | ").append(e.nextAction()).append(" |\n");}
  b.append("\nAPI = opération disponible et exercée ; COMPONENT = brique partielle, sans validation de toute la tâche ; LIMIT = refus vérifié du fragment testé ; UNVERIFIED = pas de test réalisé. PASSED pour LIMIT signifie que le refus attendu est confirmé, pas que la capacité existe. BANC_INTERFACE décrit un défaut d'exposition ou de mesure, pas une nouvelle réussite en français libre.\n\n");
  for(var t:report.cases()){b.append("## ").append(t.id()).append(" / ").append(t.probe()).append("\n\n");for(String s:t.checks())b.append("- ").append(s).append("\n");t.observations().forEach((k,v)->b.append("\n").append(k).append(" : ").append(v).append("\n"));if(!t.error().isEmpty())b.append("\nÉCHEC : ").append(t.error()).append("\n");}
  return b.toString();
 }
 public static void main(String[] args)throws Exception {
  if(args.length!=3)throw new IllegalArgumentException("DevelopmentGapAudit catalogue.tsv audit.tsv nouveau_dossier_sortie");
  Path catalog=Path.of(args[0]),manifest=Path.of(args[1]),out=Path.of(args[2]);Files.createDirectories(out);
  if(Files.exists(out.resolve("RESULTS.json")))throw new IllegalArgumentException("Dossier de résultats déjà utilisé");
  var report=run(catalog,manifest);var ids=new LinkedHashSet<String>();report.cases().forEach(t->ids.add(t.id()));var entries=readEntries(manifest,ids);
  List<Object> traces=report.cases().stream().map(t->(Object)Map.of("id",t.id(),"layer",t.layer().name(),"scope",t.scope().name(),"probe",t.probe(),"execution",t.execution(),"checks",t.checks(),"observations",t.observations(),"error",t.error())).toList();
  Files.writeString(out.resolve("RESULTS.json"),StructuredJsonWriter.json(Map.of("legacySummary",report.legacySummary(),"catalogHash",report.catalogHash(),"activationHash",report.activationHash(),"auditHash",report.auditHash(),"cases",traces)));
  Files.writeString(out.resolve("REPORT.md"),markdown(report,entries));
  long failed=report.cases().stream().filter(t->t.execution().equals("FAILED")).count(),passed=report.cases().stream().filter(t->t.execution().equals("PASSED")).count();System.out.println("AUDIT: "+report.cases().size()+" cases; "+passed+" probes passed; "+failed+" failed; "+(report.cases().size()-passed-failed)+" not run");
  if(failed>0)throw new IllegalStateException("L'audit contient des échecs ; voir RESULTS.json");
 }
}
