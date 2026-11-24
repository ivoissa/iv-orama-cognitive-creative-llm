package fr.ivorama.cognitive;
import java.util.*;
import java.net.URI;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Recherche -> hypothèses sourcées -> calcul conditionnel sur une copie locale, même acte Q. */
public final class StructuredWebReasoningService {
 public enum State { LOCAL_ONLY, NO_LEXICAL_BINDING, DOCUMENTS_ONLY, NO_DOCUMENTS, WEB_FAILED,
                     PROVISIONAL_REASONING, RESOURCE_LIMIT, INVALID_INPUT }
 public record Proposal(String id,WebSource.Hit document,StructuredWebLexicon.Literal literal){}
 public record Answer(State state,StructuredReasoningService.Answer local,
                      StructuredReasoningService.Answer assessed,List<WebSource.Hit> documents,
                      List<Proposal> proposals,List<String> errors){
  public Answer{documents=List.copyOf(documents);proposals=List.copyOf(proposals);errors=List.copyOf(errors);}
  public String describe(){StringBuilder b=new StringBuilder(local.questionId()+" | "+state+"\nBase locale : "+local.status()+"\n"+assessed.describe());
   for(var p:proposals)b.append("\nHypothèse Web NON VÉRIFIÉE ").append(p.id()).append(" : ").append(p.literal()).append(" ; ").append(p.document().url()).append(" ; récupéré ").append(p.document().retrievedAt()).append(" ; extrait : ").append(p.document().excerpt());
   if(proposals.isEmpty())for(var d:documents)b.append("\nDocument non traduit : ").append(d.url()).append(" ; extrait : ").append(d.excerpt());
   if(!documents.isEmpty()&&proposals.isEmpty())b.append("\nDocuments trouvés, aucune assertion traduite sans ambiguïté ; aucune conclusion tirée des seuls extraits.");
   for(String error:errors)b.append("\nRecherche : ").append(error);
   return b.toString();}
 }
 private record Session(Problem baseline,StructuredReasoningService.Answer local,List<WebSource.Hit> documents,
                        List<Proposal> proposals,List<String> errors,Set<URI> excluded){}
 private final IntegratedQuestionCycle cycle;
 private final StructuredReasoningService core;
 private final WebSource.Provider provider;
 private final Map<String,Session> sessions=new LinkedHashMap<>();
 public StructuredWebReasoningService(IntegratedQuestionCycle cycle,WebSource.Provider provider){this.cycle=Objects.requireNonNull(cycle);core=new StructuredReasoningService(cycle);this.provider=Objects.requireNonNull(provider);}
 public Answer ask(Problem problem,StructuredWebLexicon lexicon){
  var local=core.ask(new SemanticIntermediateRepresentation.LogicProblemFrame(problem));return investigate(problem,lexicon,local);
 }
 public Answer askJson(String json){
  String id=cycle.openGeneralQuestion(json==null||json.isBlank()?"Entrée Web structurée vide":json);
  try{
   var request=StructuredWebRequest.parse(json);
   var local=core.registerAndEvaluate(id,request.problem());return investigate(request.problem(),request.lexicon(),local);
  }catch(IllegalArgumentException ex){var invalid=core.rejectRegistered(id,"Requête Web structurée rejetée : "+ex.getMessage());return answer(State.INVALID_INPUT,invalid,invalid,List.of(),List.of(),List.of());}
 }
 private Answer investigate(Problem p,StructuredWebLexicon lexicon,StructuredReasoningService.Answer local){
  if(local.status()!=StructuredReasoningService.Status.UNDETERMINED)return answer(State.LOCAL_ONLY,local,local,List.of(),List.of(),List.of());
  var compiled=local.compilation().orElseThrow();List<GroundAtom> targets=new ArrayList<>();
  local.investigation().flatMap(RdoPlanner.Plan::choice).ifPresent(choice->{
   for(String name:Formula.atoms(choice.candidate().observation())){var target=compiled.symbols().get(name);if(target!=null&&!targets.contains(target))targets.add(target);}
  });
  // Les atomes de l'objectif sont des cibles de recherche, jamais des réponses présupposées.
  for(String name:Formula.atoms(compiled.goal())){var target=compiled.symbols().get(name);if(target!=null&&!targets.contains(target))targets.add(target);}
  List<WebSource.Hit> docs=new ArrayList<>();List<Proposal> proposals=new ArrayList<>();List<String> errors=new ArrayList<>();
  Set<String> seenDocuments=new HashSet<>(),seenProposals=new HashSet<>();Set<String> ids=new HashSet<>();p.premises().forEach(x->ids.add(x.id()));
  int queries=0,sequence=1;
  for(var target:targets){
   var positive=lexicon.render(target,true);var negative=lexicon.render(target,false);
   if(positive.isEmpty()||negative.isEmpty())continue;
   if(queries>=3)break;queries++;
   cycle.recordStructuredResearch(local.questionId(),"Recherche documentaire : "+positive.get());
   List<WebSource.Hit> hits;
   try{hits=provider.search(positive.get());}
   catch(Exception ex){errors.add(ex.getClass().getSimpleName());continue;}
   if(hits==null)continue;
   int inspected=0;
   for(var hit:hits){
    if(++inspected>48)break;
    if(!OntologyWebReasoningService.validHit(hit))continue;
    if(docs.size()>=12)break;
    String key=hit.url()+"\n"+hit.excerpt();if(!seenDocuments.add(key))continue;docs.add(hit);
    var literal=lexicon.extract(hit.excerpt(),compiled.symbols().values());if(literal.isEmpty())continue;
    String proposalKey=hit.url()+"\n"+literal.get();if(!seenProposals.add(proposalKey))continue;
    String id;do{id="web_hypothesis_"+sequence++;}while(!ids.add(id));
    proposals.add(new Proposal(id,hit,literal.get()));
    cycle.recordStructuredResearch(local.questionId(),"Hypothèse NON VÉRIFIÉE "+id+" : "+literal.get()+" ; "+hit.url()+" ; "+hit.retrievedAt()+" ; extrait : "+hit.excerpt());
   }
  }
  Session s=new Session(p,local,List.copyOf(docs),List.copyOf(proposals),List.copyOf(errors),Set.of());sessions.put(local.questionId(),s);
  if(proposals.isEmpty()){
   State state=queries==0?State.NO_LEXICAL_BINDING:!docs.isEmpty()?State.DOCUMENTS_ONLY:!errors.isEmpty()?State.WEB_FAILED:State.NO_DOCUMENTS;
   return answer(state,local,local,docs,proposals,errors);
  }
  return assess(s);
 }
 private Answer assess(Session s){
  List<Premise> premises=new ArrayList<>(s.baseline().premises());List<Proposal> active=new ArrayList<>();
  for(var proposal:s.proposals())if(!s.excluded().contains(proposal.document().url())){
   var hit=proposal.document();premises.add(new Premise(proposal.id(),proposal.literal().expression(),new Evidence(Origin.WEB_EXCERPT_UNVERIFIED,hit.url()+" ; "+hit.provider()+" ; "+hit.retrievedAt(),hit.excerpt())));active.add(proposal);
  }
  var p=s.baseline();var temporary=new Problem(p.sourceText(),p.entities(),p.predicates(),premises,p.goal(),p.observables(),p.unresolved());
  var assessed=core.evaluateScoped(s.local().questionId(),temporary);
  State state=assessed.status()==StructuredReasoningService.Status.RESOURCE_LIMIT?State.RESOURCE_LIMIT:active.isEmpty()?State.LOCAL_ONLY:State.PROVISIONAL_REASONING;
  return answer(state,s.local(),assessed,s.documents(),active,s.errors());
 }
 public Answer revisit(String id){var s=sessions.get(id);if(s==null){var local=core.revisit(id);return answer(State.LOCAL_ONLY,local,local,List.of(),List.of(),List.of());}return assess(s);}
 public Answer excludeSource(String id,URI url){
  var s=sessions.get(id);if(s==null)throw new IllegalArgumentException("Recherche inconnue");
  Set<URI> excluded=new HashSet<>(s.excluded());excluded.add(url);
  var next=new Session(s.baseline(),s.local(),s.documents(),s.proposals(),s.errors(),Set.copyOf(excluded));sessions.put(id,next);
  cycle.recordStructuredResearch(id,"Source exclue : "+url);return assess(next);
 }
 private Answer answer(State state,StructuredReasoningService.Answer local,StructuredReasoningService.Answer assessed,List<WebSource.Hit> docs,List<Proposal> proposals,List<String> errors){
  cycle.recordStructuredResearch(local.questionId(),"Résultat Web : "+state+" ; hypothèses non vérifiées, aucune promotion permanente");
  return new Answer(state,local,assessed,docs,proposals,errors);
 }
}
