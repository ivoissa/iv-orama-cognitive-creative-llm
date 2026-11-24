package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** FCI -> JSON sémantique -> contraintes JSON -> solveurs existants, sous le même acte Q. */
public final class FrenchStructuredReasoningService {
 public record Turn(FrenchStructuredTranslator.Translation translation,StructuredReasoningService.Answer answer,
                    Optional<String> narrativeJson,Map<String,StructuredReasoningService.Status> candidates,Optional<String> selected){
  public Turn{narrativeJson=Objects.requireNonNull(narrativeJson);candidates=Collections.unmodifiableMap(new LinkedHashMap<>(candidates));}
  public Turn(FrenchStructuredTranslator.Translation translation,StructuredReasoningService.Answer answer){this(translation,answer,Optional.empty(),Map.of(),Optional.empty());}
  public String describe(){return narrativeJson.map(j->"JSON sémantique événementiel :\n"+j+"\n").orElse("")+"Interprétation : "+translation.symbols()+"\nJSON "+(translation.problem().isPresent()?"généré":"diagnostique")+" :\n"+translation.json()+"\n"+answer.describe()+
    (candidates.isEmpty()?"":"\nSélection dans le domaine nommé : "+candidates)+selected.map(s->"\nRéponse sous les conventions narratives : "+s).orElse("")+
    (narrativeJson.isEmpty()?"":"\nConventions : récit fini ordonné, événements accomplis, détention exclusive (pas propriété juridique), persistance des seuls états modélisés. Aucune vérité empirique certifiée.");}
 }
 private record Saved(FrenchStructuredTranslator.Translation translation,Optional<String> semanticJson,Map<String,Expression> candidates,Map<String,String> labels,Optional<StructuredReasoningService.Answer> rejection,Optional<Expression> presupposition){}
 private final IntegratedQuestionCycle cycle;private final StructuredReasoningService core;
 private final Map<String,Saved> translations=new LinkedHashMap<>();
 private final FrenchStructuredTranslator translator=new FrenchStructuredTranslator();private final FrenchEventTranslator eventTranslator=new FrenchEventTranslator();
 private final EventLogicCompiler eventCompiler=new EventLogicCompiler(EventRuleRegistry.defaults());
 public FrenchStructuredReasoningService(IntegratedQuestionCycle cycle){this.cycle=Objects.requireNonNull(cycle);core=new StructuredReasoningService(cycle);}
 public boolean hasQuestion(String id){return translations.containsKey(id);}
 public Turn revisit(String id){
  var saved=translations.get(id);if(saved==null)throw new IllegalArgumentException("Question française inconnue");
  var answer=saved.rejection().orElseGet(()->saved.translation().problem().isPresent()?core.revisit(id):core.incompleteRegistered(id,String.join(" ; ",saved.translation().unresolved())));
  return finish(saved,answer);
 }
 public Turn respond(String text){
  String id=cycle.openGeneralQuestion(text==null||text.isBlank()?"Entrée française vide":text);
  var translated=translator.translate(text);Optional<String> semanticJson=Optional.empty();Map<String,Expression> candidates=Map.of();Map<String,String> labels=Map.of();Optional<Expression> presupposition=Optional.empty();
  if(!translated.complete()){
   var events=eventTranslator.translate(text);
   if(events.complete()){
    semanticJson=Optional.of(events.json());
    try{
     // Le compilateur consomme réellement la représentation relue, jamais le récit avant sérialisation.
     var narrative=EventNarrative.read(events.json());var compiled=eventCompiler.compile(narrative);var problem=compiled.problem();
     List<FrenchStructuredTranslator.Segment> spans=new ArrayList<>();
     narrative.initial().forEach(f->spans.add(segment(f.span(),false)));narrative.events().forEach(e->spans.add(segment(e.span(),false)));spans.add(segment(narrative.query().span(),true));
     Map<String,String> symbols=new LinkedHashMap<>();narrative.entities().forEach((k,v)->symbols.put("entity:"+k,v));
     translated=new FrenchStructuredTranslator.Translation(Optional.of(problem),StructuredJsonWriter.write(problem),spans,symbols,List.of());
     candidates=compiled.candidates();labels=narrative.entities();presupposition=compiled.presupposition();
    }catch(IllegalArgumentException ex){
     var rejected=new FrenchStructuredTranslator.Translation(Optional.empty(),events.json(),List.of(),Map.of(),List.of(ex.getMessage()));
     var rejection=core.rejectRegistered(id,"Compilation événementielle rejetée : "+ex.getMessage());
     var saved=new Saved(rejected,semanticJson,Map.of(),Map.of(),Optional.of(rejection),Optional.empty());translations.put(id,saved);return finish(saved,rejection);
    }
   }else if(eventTranslator.looksNarrative(text)){
    translated=new FrenchStructuredTranslator.Translation(Optional.empty(),events.json(),List.of(),Map.of(),events.unresolved());
   }
  }
  var saved=new Saved(translated,semanticJson,candidates,labels,Optional.empty(),presupposition);translations.put(id,saved);
  if(translated.problem().isEmpty())return finish(saved,core.incompleteRegistered(id,String.join(" ; ",translated.unresolved())));
  try{return finish(saved,core.registerAndEvaluate(id,StructuredProblemJson.parse(translated.json())));}
  catch(IllegalArgumentException ex){return finish(saved,core.rejectRegistered(id,"JSON produit non admissible : "+ex.getMessage()));}
 }
 private static FrenchStructuredTranslator.Segment segment(EventNarrative.Span s,boolean question){return new FrenchStructuredTranslator.Segment(s.start(),s.end(),s.excerpt(),question,true,"Rôles et événement représentés");}
 private Turn finish(Saved s,StructuredReasoningService.Answer main){
  Map<String,StructuredReasoningService.Status> statuses=new LinkedHashMap<>();Optional<String> selected=Optional.empty();
  if(!s.candidates().isEmpty()&&main.analysis().isPresent()&&main.status()!=StructuredReasoningService.Status.INCONSISTENT&&main.status()!=StructuredReasoningService.Status.RESOURCE_LIMIT){
   Problem p=s.translation().problem().orElseThrow();
   for(var candidate:s.candidates().entrySet()){
    var goal=new Problem(p.sourceText(),p.entities(),p.predicates(),p.premises(),candidate.getValue(),p.observables(),p.unresolved());
    statuses.put(s.labels().get(candidate.getKey()),core.evaluateScoped(main.questionId(),goal).status());
   }
   var certain=statuses.entrySet().stream().filter(e->e.getValue()==StructuredReasoningService.Status.TRUE_CONDITIONALLY).toList();
   if(certain.size()==1&&statuses.values().stream().allMatch(v->v==StructuredReasoningService.Status.TRUE_CONDITIONALLY||v==StructuredReasoningService.Status.FALSE_CONDITIONALLY))selected=Optional.of(certain.get(0).getKey());
   main=core.revisit(main.questionId()); // Le dernier statut de Q reste celui de l'objectif enregistré.
  }
  if(s.presupposition().isPresent()&&main.analysis().isPresent()&&main.status()!=StructuredReasoningService.Status.INCONSISTENT&&main.status()!=StructuredReasoningService.Status.RESOURCE_LIMIT){
   Problem p=s.translation().problem().orElseThrow();
   Problem historical=StructuredProblemJson.parse(StructuredJsonWriter.write(new Problem(p.sourceText(),p.entities(),p.predicates(),p.premises(),s.presupposition().orElseThrow(),p.observables(),p.unresolved())));
   var previous=core.evaluateScoped(main.questionId(),historical);main=core.revisit(main.questionId());
   if(previous.status()!=StructuredReasoningService.Status.TRUE_CONDITIONALLY){
    String reason=previous.status()==StructuredReasoningService.Status.FALSE_CONDITIONALLY?"contredite":"non établie";
    main=core.incompleteRegistered(main.questionId(),"Présupposition temporelle d'encore "+reason+" dans l'état modélisé précédant l'instant interrogé. Préciser le point de référence ; aucun passé inventé.");
   }else main=new StructuredReasoningService.Answer(main.questionId(),main.status(),main.explanation()+" Présupposition d'encore vérifiée dans l'état modélisé immédiatement précédent, sous les conventions du récit fini.",main.compilation(),main.analysis(),main.checkedProof(),main.conflictPremises(),main.investigation(),Optional.empty(),main.semanticExplanation());
  }
  return new Turn(s.translation(),main,s.semanticJson(),statuses,selected);
 }
}
