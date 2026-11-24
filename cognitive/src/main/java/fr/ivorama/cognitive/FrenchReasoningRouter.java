package fr.ivorama.cognitive;
import java.util.*;

/** Choisit une intention documentaire avant le raisonnement structuré ; conserve les deux résultats distincts. */
public final class FrenchReasoningRouter {
 /** Trois modes exclusifs : logique, documentaire, ou acte directif (qui n'ouvre aucune question). */
 public record Turn(Optional<FrenchStructuredReasoningService.Turn> logical,Optional<WikipediaDefinitionService.Answer> documentary,Optional<DirectiveActs.Directive> directive,Optional<TextTaskService.Result> execution){
  public Turn(Optional<FrenchStructuredReasoningService.Turn> logical,Optional<WikipediaDefinitionService.Answer> documentary){this(logical,documentary,Optional.empty(),Optional.empty());}
  public Turn(Optional<FrenchStructuredReasoningService.Turn> logical,Optional<WikipediaDefinitionService.Answer> documentary,Optional<DirectiveActs.Directive> directive){this(logical,documentary,directive,Optional.empty());}
  public Turn{if(execution.isPresent()&&directive.isEmpty())throw new IllegalArgumentException("Exécution sans demande");if((logical.isPresent()?1:0)+(documentary.isPresent()?1:0)+(directive.isPresent()?1:0)!=1)throw new IllegalArgumentException("Un seul mode de réponse requis");}
  public String describe(){return execution.map(TextTaskService.Result::describe).orElseGet(()->directive.map(DirectiveActs.Directive::describe).orElseGet(()->documentary.map(WikipediaDefinitionService.Answer::describe).orElseGet(()->logical.orElseThrow().describe())));}
 }
 private final TextTaskService tasks;
 private final FrenchStructuredReasoningService logical;private final WikipediaDefinitionService wikipedia;
 public FrenchReasoningRouter(IntegratedQuestionCycle cycle){this(cycle,new WebSource.Wikipedia());}
 public FrenchReasoningRouter(IntegratedQuestionCycle cycle,WebSource.Provider provider){var lexicon=new LexicalKnowledgeService(provider);tasks=new TextTaskService(new CreativeTextEngine(),632L,provider,lexicon);logical=new FrenchStructuredReasoningService(cycle);wikipedia=new WikipediaDefinitionService(cycle,provider,lexicon);}
 public Turn respond(String text){
  // Une demande est exécutée sans être transformée en question logique.
  var directive=DirectiveActs.classify(text);
  if(directive.isPresent()&&text.length()>8000)return new Turn(Optional.empty(),Optional.empty(),directive,Optional.of(TextTaskService.inputLimit(directive.orElseThrow())));
  if(directive.isPresent()){var d=DirectiveActs.read(directive.orElseThrow().json());return new Turn(Optional.empty(),Optional.empty(),Optional.of(d),Optional.of(tasks.execute(d)));}
  return respondQuestion(text);
 }
 private Turn respondQuestion(String text){return WikipediaQuestion.isCandidate(text)?new Turn(Optional.empty(),Optional.of(wikipedia.respond(text))):new Turn(Optional.of(logical.respond(text)),Optional.empty());}
 public List<String> textTaskIds(){return tasks.taskIds();}
 public TextTaskService.Result revisitTextTask(String id){return tasks.revisit(id);}
 public TextTaskService.Result excludeTextSource(String id,java.net.URI uri){return tasks.exclude(id,uri);}
 public boolean hasQuestion(String id){return wikipedia.hasQuestion(id)||logical.hasQuestion(id);}
 public Turn revisit(String id){return wikipedia.hasQuestion(id)?new Turn(Optional.empty(),Optional.of(wikipedia.revisit(id))):new Turn(Optional.of(logical.revisit(id)),Optional.empty());}
}
