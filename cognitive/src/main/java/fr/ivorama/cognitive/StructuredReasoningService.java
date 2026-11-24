package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Adaptateur RSI -> solveurs existants. Contexte isolé, aucune réponse propre à un domaine. */
public final class StructuredReasoningService {
 public enum Status { TRUE_CONDITIONALLY,FALSE_CONDITIONALLY,UNDETERMINED,INCONSISTENT,
                      INCOMPLETE_INTERPRETATION,INVALID_INPUT,RESOURCE_LIMIT }
 public record Answer(String questionId,Status status,String explanation,
                      Optional<StructuredLogicCompiler.Compiled> compilation,
                      Optional<ModelExplorer.Analysis> analysis,Optional<ProofChecker.Result> checkedProof,
                      List<String> conflictPremises,Optional<RdoPlanner.Plan> investigation,
                      Optional<GoalDecomposition.Report> goalDetails,Optional<SemanticExplanation> semanticExplanation){
  public Answer{conflictPremises=List.copyOf(conflictPremises);Objects.requireNonNull(semanticExplanation);}
  public Answer(String questionId,Status status,String explanation,
                Optional<StructuredLogicCompiler.Compiled> compilation,Optional<ModelExplorer.Analysis> analysis,
                Optional<ProofChecker.Result> checkedProof,List<String> conflictPremises,Optional<RdoPlanner.Plan> investigation,
                Optional<GoalDecomposition.Report> goalDetails) {
   this(questionId,status,explanation,compilation,analysis,checkedProof,conflictPremises,investigation,goalDetails,Optional.empty());
  }
  public Answer(String questionId,Status status,String explanation,
                Optional<StructuredLogicCompiler.Compiled> compilation,Optional<ModelExplorer.Analysis> analysis,
                Optional<ProofChecker.Result> checkedProof,List<String> conflictPremises,Optional<RdoPlanner.Plan> investigation) {
   this(questionId,status,explanation,compilation,analysis,checkedProof,conflictPremises,investigation,Optional.empty());
  }
  public String describe(){
   String reference=describeReference();
   return semanticExplanation.isEmpty()?reference:reference+"\n"+semanticExplanation.orElseThrow().describe();
  }
  private String describeReference(){return questionId+" | "+status+"\n"+explanation+
    checkedProof.map(p->"\nPreuve syntaxique vérifiée ; dépendances : "+p.dependencies()).orElse("")+
    analysis.flatMap(ModelExplorer.Analysis::trueWitness).map(w->"\nModèle témoin vrai : "+decode(w)).orElse("")+
    analysis.flatMap(ModelExplorer.Analysis::falseWitness).map(w->"\nModèle témoin faux : "+decode(w)).orElse("")+
    (conflictPremises.isEmpty()?"":"\nPrémisses du conflit : "+conflictPremises)+
    goalDetails.map(d->"\nSous-buts (même contexte ; verdict conjoint conservé) : "+d.nodes()+
      (d.truncated()?" ; diagnostic tronqué par budget":"")).orElse("")+
    investigation.map(p->"\nRDO : "+p.explanation()+" ; observations proposées : "+p.plannedSequence()).orElse("");}
  private Map<GroundAtom,Boolean> decode(ModelExplorer.World w){
   Map<GroundAtom,Boolean> result=new LinkedHashMap<>();
   compilation.orElseThrow().symbols().forEach((name,atom)->{if(w.values().containsKey(name))result.put(atom,w.values().get(name));});return result;
  }
 }
 private final IntegratedQuestionCycle cycle;
 private final ModelExplorer.Budget budget;
 public StructuredReasoningService(IntegratedQuestionCycle cycle){this(cycle,ModelExplorer.Budget.small());}
 public StructuredReasoningService(IntegratedQuestionCycle cycle,ModelExplorer.Budget budget){this.cycle=Objects.requireNonNull(cycle);this.budget=Objects.requireNonNull(budget);}
 public Answer ask(SemanticIntermediateRepresentation.LogicProblemFrame frame){
  Objects.requireNonNull(frame);String id=cycle.openGeneralQuestion(frame.problem().sourceText());
  cycle.recordStructuredRepresentation(id,frame);return evaluate(id,frame.problem());
 }
 public Answer askJson(String json){
  String id=cycle.openGeneralQuestion(json==null||json.isBlank()?"Entrée structurée vide":json);
  try{
   var frame=new SemanticIntermediateRepresentation.LogicProblemFrame(StructuredProblemJson.parse(json));
   cycle.recordStructuredRepresentation(id,frame);return evaluate(id,frame.problem());
  }catch(IllegalArgumentException ex){return empty(id,Status.INVALID_INPUT,"Entrée JSON rejetée : "+ex.getMessage());}
 }
 public Answer revisit(String id){return evaluate(id,cycle.structuredRepresentation(id).orElseThrow(()->new IllegalArgumentException("Aucune RSI structurée pour cet acte")).problem());}
 Answer evaluateScoped(String id,Problem p){
  cycle.structuredRepresentation(id).orElseThrow(()->new IllegalArgumentException("Acte structuré requis"));
  return evaluate(id,p);
 }
 Answer registerAndEvaluate(String id,Problem p){
  cycle.recordStructuredRepresentation(id,new SemanticIntermediateRepresentation.LogicProblemFrame(p));return evaluate(id,p);
 }
 Answer incompleteRegistered(String id,String text){return empty(id,Status.INCOMPLETE_INTERPRETATION,text);}
 Answer rejectRegistered(String id,String text){return empty(id,Status.INVALID_INPUT,text);}
 private Answer evaluate(String id,Problem p){
  if(!p.unresolved().isEmpty())return empty(id,Status.INCOMPLETE_INTERPRETATION,"Interprétation incomplète : "+p.unresolved()+". Aucun fragment ignoré pour produire une conclusion.");
  StructuredLogicCompiler.Compiled c;
  try{c=StructuredLogicCompiler.compile(p);}
  catch(StructuredLogicCompiler.Limit ex){return empty(id,Status.RESOURCE_LIMIT,ex.getMessage());}
  catch(IllegalArgumentException ex){return empty(id,Status.INVALID_INPUT,ex.getMessage());}
  var a=cycle.assessSemantics(c.premises(),c.goal(),budget);
  Status status=switch(a.status()){
   case PROVEN_TRUE -> Status.TRUE_CONDITIONALLY;
   case PROVEN_FALSE -> Status.FALSE_CONDITIONALLY;
   case UNDETERMINED -> Status.UNDETERMINED;
   case INCONSISTENT -> Status.INCONSISTENT;
   case RESOURCE_LIMIT -> Status.RESOURCE_LIMIT;
  };
  Optional<ProofChecker.Result> proof=Optional.empty();
  if(a.status()==ModelExplorer.Status.PROVEN_TRUE||a.status()==ModelExplorer.Status.PROVEN_FALSE){
   Formula target=a.status()==ModelExplorer.Status.PROVEN_TRUE?c.goal():Formula.not(c.goal());
   proof=ProofSearch.find(c.premises(),target,512).map(pr->ProofChecker.verify(pr,c.premises())).filter(r->r.valid()&&r.conclusion().equals(target));
  }
  List<String> conflict=new ArrayList<>();
  if(status==Status.INCONSISTENT){
   var core=new LinkedHashMap<>(c.premiseIds());
   for(String key:List.copyOf(core.keySet())){
    var trial=new LinkedHashMap<>(core);trial.remove(key);
    if(cycle.assessSemantics(trial.values(),Formula.Bottom.INSTANCE,budget).status()==ModelExplorer.Status.INCONSISTENT)core=trial;
   }
   conflict.addAll(core.keySet());
  }
  Optional<RdoPlanner.Plan> plan=Optional.empty();
  if(status==Status.UNDETERMINED&&!c.observables().isEmpty())plan=Optional.of(RdoPlanner.propose(c.premises(),c.goal(),c.observables(),budget));
  String text=switch(status){
   case TRUE_CONDITIONALLY -> "La proposition est vraie dans tous les modèles compatibles, sous les prémisses fournies.";
   case FALSE_CONDITIONALLY -> "La proposition est fausse dans tous les modèles compatibles, sous les prémisses fournies.";
   case UNDETERMINED -> "Les prémisses permettent une réponse vraie et une réponse fausse : deux témoins sont fournis.";
   case INCONSISTENT -> "Les prémisses sont incompatibles : aucune conclusion par explosion. Le conflit listé est un sous-ensemble incompatible, pas nécessairement de cardinalité minimale.";
   default -> "Budget dépassé : aucun statut logique conclusif ni ensemble partiel de mondes présenté comme complet.";
  };
  text+=" Domaine fini explicitement déclaré ; aucune vérité empirique certifiée. "+a.explanation();
  if (!conflict.isEmpty()) {
   var conflictingIds = new HashSet<>(conflict);
   var details = new StringBuilder("\nSources déclarées du conflit (non certifiées empiriquement) :");
   for (var premise : p.premises()) {
    if (conflictingIds.contains(premise.id())) {
     details.append('\n').append(premise.id()).append(" | source=")
         .append(StructuredJsonWriter.json(premise.evidence().source())).append(" | extrait=")
         .append(StructuredJsonWriter.json(premise.evidence().excerpt()));
    }
   }
   text += details;
  }
  cycle.recordStructuredFinding(id,status.name());
  Optional<GoalDecomposition.Report> details=c.goal() instanceof Formula.And
      ?Optional.of(GoalDecomposition.analyze(c.premises(),c.goal(),a,budget,17)):Optional.empty();
  Optional<SemanticExplanation> semanticExplanation=cycle.explanationsEnabled()?cycle.explainDeclared(p.premises().stream()
      .map(source->new SemanticExplanation.Premise(source.id(),c.premiseIds().get(source.id()),
          source.evidence().source(),source.evidence().excerpt())).toList(),c.goal(),a.status()):Optional.empty();
  return new Answer(id,status,text,Optional.of(c),Optional.of(a),proof,conflict,plan,details,semanticExplanation);
 }
 private Answer empty(String id,Status status,String text){
  cycle.recordStructuredFinding(id,status.name());return new Answer(id,status,text,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
 }
}
