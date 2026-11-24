package fr.ivorama.cognitive;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Couche additive avant l'ontologie ; les analyses sont des candidats, pas des faits. */
public final class QuestionOrientationDialogue {
 public enum Status { DELEGATED, NEEDS_LOCATION, NEEDS_DATE, NEEDS_OBSERVATION,
     NEEDS_INTENT, CONTEXT_UNDETERMINED, CONTEXT_CONDITIONAL, CONTEXT_INCONSISTENT, DOCUMENTS_ONLY, NO_DOCUMENTS, WEB_FAILED, STALE_RESEARCH }
 public record Turn(String questionId,Status status,String text,FrenchQuestionAnalyzer.Analysis analysis,
                    QuestionActionPlanner.Plan plan,List<WebSource.Hit> documents){
  public Turn {documents=List.copyOf(documents);}
  public String describe(){return questionId+" | "+status+"\n"+text;}
 }
 private record Pending(String id,FrenchQuestionAnalyzer.Analysis analysis){}
 private final OntologicalKnowledgeGraph graph;
 private final IntegratedQuestionCycle cycle;
 private final WebSource.Provider provider;
 private final FrenchQuestionAnalyzer analyzer;
 private final QuestionActionPlanner planner=new QuestionActionPlanner();
 private final OntologyConversation ontology;
 private final Map<String,FrenchQuestionAnalyzer.Analysis> analyses=new LinkedHashMap<>();
 private Pending pending;
 private String legacyPending;
 public QuestionOrientationDialogue(OntologicalKnowledgeGraph graph,IntegratedQuestionCycle cycle,WebSource.Provider provider,Clock clock){
  this.graph=Objects.requireNonNull(graph);this.cycle=Objects.requireNonNull(cycle);this.provider=Objects.requireNonNull(provider);
  analyzer=new FrenchQuestionAnalyzer(clock);ontology=new OntologyConversation(graph,cycle,provider);
 }
 void clearContext(){pending=null;legacyPending=null;ontology.clearContext();}
 public Optional<FrenchQuestionAnalyzer.Analysis> analysis(String id){return Optional.ofNullable(analyses.get(id));}
 public Turn respond(String text){
  if(text==null || text.isBlank() || text.length()>2000)throw new IllegalArgumentException("Énoncé vide ou trop long");
  boolean question=looksLikeQuestion(text) || ((legacyPending!=null || (pending!=null && pending.analysis().kind()!=FrenchQuestionAnalyzer.Kind.CONTEXTUAL_VISIBILITY)) && analyzer.analyze(text).kind()!=FrenchQuestionAnalyzer.Kind.UNSUPPORTED);
  if(legacyPending!=null && !question){
   String id=legacyPending;
   var response=ontology.respond(FrenchQuestionAnalyzer.normalize(text));
   legacyPending=response.status()==OntologyConversation.Status.NEEDS_CLARIFICATION?id:null;
   return new Turn(id,Status.DELEGATED,response.describe(),analyses.get(id),planner.plan(analyses.get(id)),response.documents());
  }
  if(pending!=null && !question)return complete(text);
  pending=null;legacyPending=null;
  String id=cycle.openGeneralQuestion(text); // L'acte précède toute tentative d'analyse.
  var analysis=analyzer.analyze(text);
  return route(id,analysis);
 }
 private static boolean looksLikeQuestion(String text){
  String t=FrenchQuestionAnalyzer.normalize(text);
  return t.contains("?") || t.startsWith("pourquoi") || t.startsWith("et pourquoi") ||
      t.matches("^(?:quel|quelle|quels|quelles|comment|qui|est-ce).*" ) ||
      t.matches(".*\\b(?:est|a)[ -]+(?:t[ -]+)?(?:il|elle|ils|elles)\\b.*");
 }
 private Turn complete(String text){
  var p=pending;var a=p.analysis();String clean=text.strip();
  var action=planner.plan(a).action();
  if(action==QuestionActionPlanner.Action.ANALYZE_CONTEXT){
   var frame=a.context().orElseThrow();
   String combined=(frame.premises().isEmpty()?"":String.join("; ",frame.premises())+"; ")+clean+"; "+frame.question();
   if(combined.length()>8000)return result(p.id(),Status.NEEDS_INTENT,"Contexte trop long ; reformule une question complète.",a,List.of());
   var next=new ContextualVisibilityReasoner().analyze(combined);
   if(next.isEmpty() || !next.get().uninterpreted().isEmpty())return result(p.id(),Status.NEEDS_INTENT,
     "Précision non interprétée ; reformule une condition explicite. Le contexte précédent est conservé.",a,List.of());
   a=new FrenchQuestionAnalyzer.Analysis(a.original(),a.canonical(),a.kind(),a.subject(),a.relation(),a.target(),
       a.date(),a.location(),a.missing(),a.ontologyCompatible(),next);
  }else if(action==QuestionActionPlanner.Action.REQUEST_LOCATION){
   String place=clean.replaceFirst("(?iu)^(?:à |a |au |aux |en )", "").strip();
   if(!place.matches("[\\p{L}][\\p{L}'’ -]{0,79}"))return result(p.id(),Status.NEEDS_LOCATION,"Lieu non interprété ; donne un nom de lieu simple.",a,List.of());
   a=a.withLocation(place);
  }else if(action==QuestionActionPlanner.Action.REQUEST_DATE){
   try{a=a.withDate(LocalDate.parse(clean));}catch(DateTimeParseException ex){return result(p.id(),Status.NEEDS_DATE,"Date non interprétée ; utilise AAAA-MM-JJ.",a,List.of());}
  }else if(action==QuestionActionPlanner.Action.REQUEST_OBSERVATION){
   cycle.recordOntologyClarification(p.id(),"Observation rapportée, non vérifiée : "+clean);
   return result(p.id(),Status.NEEDS_OBSERVATION,"Observation reçue comme déclaration : « "+clean+" ». Elle ne suffit pas à établir l'état « "+a.target()+" » de « "+a.subject()+" ». Précise son identité, le contexte et les observations directes ; aucune conclusion n'est certifiée.",a,List.of());
  }else return result(p.id(),Status.NEEDS_INTENT,"Précise la question complète ; je ne peux pas choisir son sens arbitrairement.",a,List.of());
  cycle.recordOntologyClarification(p.id(),clean);
  pending=null;return route(p.id(),a);
 }
 private Turn route(String id,FrenchQuestionAnalyzer.Analysis a){
  analyses.put(id,a);cycle.recordQuestionAnalysis(id,a);
  if(!a.ontologyCompatible())ontology.clearContext();
  var plan=planner.plan(a);
  switch(plan.action()){
   case USE_EXISTING_ENGINE -> {
    var response=ontology.respondRegistered(id,a.canonical());
    legacyPending=response.status()==OntologyConversation.Status.NEEDS_CLARIFICATION?id:null;
    return new Turn(id,Status.DELEGATED,response.describe(),a,plan,response.documents());
   }
   case REQUEST_LOCATION -> {pending=new Pending(id,a);return result(id,Status.NEEDS_LOCATION,"Pour quel lieu ? Date interprétée : "+a.date().map(Object::toString).orElse("à préciser")+". Aucun lieu n'est supposé.",a,List.of());}
   case REQUEST_DATE -> {pending=new Pending(id,a);return result(id,Status.NEEDS_DATE,"Pour quelle date ? Indique AAAA-MM-JJ. Lieu fourni : "+a.location().orElse("à préciser")+".",a,List.of());}
   case REQUEST_OBSERVATION -> {pending=new Pending(id,a);return result(id,Status.NEEDS_OBSERVATION,"De quel individu « "+a.subject()+" » parles-tu, et quelles observations récentes suggèrent l'état « "+a.target()+" » ? Une recherche générale ne permet pas d'établir cet état individuel.",a,List.of());}
   case CLARIFY_INTENT -> {return result(id,Status.NEEDS_INTENT,"Je ne peux pas interpréter cette forme ou sa portée. Précise le sujet et ce que tu veux savoir ; aucune réponse ni recherche arbitraire.",a,List.of());}
   case ANALYZE_CONTEXT -> {
    var assessment=new ContextualVisibilityReasoner().assess(a.context().orElseThrow());
    Status state=switch(assessment.finding()){
     case UNDETERMINED -> Status.CONTEXT_UNDETERMINED;
     case IMPOSSIBLE_CONDITIONALLY, POSSIBLE_CONDITIONALLY -> Status.CONTEXT_CONDITIONAL;
     case INCONSISTENT -> Status.CONTEXT_INCONSISTENT;
     case NEEDS_INTERPRETATION -> Status.NEEDS_INTENT;
    };
    if(assessment.finding()==ContextualVisibilityReasoner.Finding.UNDETERMINED || assessment.finding()==ContextualVisibilityReasoner.Finding.NEEDS_INTERPRETATION)pending=new Pending(id,a);
    return result(id,state,assessment.explanation(),a,List.of());
   }
   case SEARCH_DOCUMENTS -> {return search(id,a);}
   default -> throw new IllegalStateException();
  }
 }
 private Turn search(String id,FrenchQuestionAnalyzer.Analysis a){
  String terms=a.kind()==FrenchQuestionAnalyzer.Kind.DATED_VALUE?
    a.relation()+" "+a.location().orElseThrow()+" "+a.date().orElseThrow():a.subject()+" "+a.target();
  if(terms.length()>180)return result(id,Status.NEEDS_INTENT,"Requête trop longue ; précise les termes.",a,List.of());
  long version=graph.version();List<WebSource.Hit> documents;
  try{var hits=provider.search(terms);documents=hits==null?List.of():hits.stream().filter(OntologyWebReasoningService::validHit).limit(12).toList();}
  catch(Exception ex){return result(id,Status.WEB_FAILED,"Recherche demandée (« "+terms+" »), mais échec fournisseur : "+ex.getClass().getSimpleName()+". Aucune donnée inventée.",a,List.of());}
  if(graph.version()!=version)return result(id,Status.STALE_RESEARCH,"Le graphe a changé ; résultats abandonnés.",a,List.of());
  if(documents.isEmpty())return result(id,Status.NO_DOCUMENTS,"Recherche effectuée (« "+terms+" »), aucun document utilisable. Aucune conclusion établie.",a,List.of());
  StringBuilder response=new StringBuilder("Recherche documentaire : "+terms+". Extraits non vérifiés, pas une conclusion :");
  for(var d:documents)response.append("\n- ").append(d.url()).append("\n  ").append(d.excerpt());
  if(a.kind()==FrenchQuestionAnalyzer.Kind.DATED_VALUE)response.append("\nUne valeur numérique exige une source adaptée au lieu, à la date, à la mesure et à son unité. Les extraits seuls ne valident pas une observation météo.");
  return result(id,Status.DOCUMENTS_ONLY,response.toString(),a,documents);
 }
 public Turn revisit(String id){
  var a=analyses.get(id);if(a==null)throw new IllegalArgumentException("Question inconnue");
  if(a.ontologyCompatible() && ontology.hasSession(id)){
   var response=ontology.revisit(id);return new Turn(id,Status.DELEGATED,response.describe(),a,planner.plan(a),response.documents());
  }
  return result(id,Status.NEEDS_INTENT,"Question conservée, aucune nouvelle observation ni recherche implicite. Précise ou repose la question pour investiguer.",a,List.of());
 }
 private Turn result(String id,Status status,String text,FrenchQuestionAnalyzer.Analysis a,List<WebSource.Hit> documents){
  cycle.recordOntologyFinding(id,status.name(),graph.version());return new Turn(id,status,text,a,planner.plan(a),documents);
 }
}
