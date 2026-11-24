package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;

/** Exécution locale des tâches textuelles reconnues ; aucun acte Q ni fait global créé. */
public final class TextTaskService {
 public enum Status { CREATED,DOCUMENTED_TEXT,EXTRACTED_SUMMARY,PARTIAL_SUMMARY,ANALYZED,PROVISIONAL_ANALYSIS,PARTIAL_ANALYSIS,RESOURCE_LIMIT,INCONSISTENT_ANALYSIS,NEEDS_INFORMATION,UNSUPPORTED_TASK,COMPOSITE_NEEDS_CLARIFICATION }
 public record Result(Status status,String output,String requestJson,Optional<CreativeTextEngine.Composition> composition,Optional<TextContextAnalysis.Result> analysis,Optional<TextWebContextService.Answer> webContext,Optional<FrenchDiscourse.Document> discourse,Optional<LexicalKnowledgeService.Trace> lexical){
  public Result(Status status,String output,String requestJson,Optional<CreativeTextEngine.Composition> composition,Optional<TextContextAnalysis.Result> analysis,Optional<TextWebContextService.Answer> webContext,Optional<FrenchDiscourse.Document> discourse){this(status,output,requestJson,composition,analysis,webContext,discourse,Optional.empty());}
  public Result(Status status,String output,String requestJson,Optional<CreativeTextEngine.Composition> composition,Optional<TextContextAnalysis.Result> analysis,Optional<TextWebContextService.Answer> webContext){this(status,output,requestJson,composition,analysis,webContext,Optional.empty());}
  public Result(Status status,String output,String requestJson,Optional<CreativeTextEngine.Composition> composition,Optional<TextContextAnalysis.Result> analysis){this(status,output,requestJson,composition,analysis,Optional.empty());}
  public Result{Objects.requireNonNull(lexical);Objects.requireNonNull(discourse);Objects.requireNonNull(webContext);Objects.requireNonNull(status);Objects.requireNonNull(output);Objects.requireNonNull(requestJson);Objects.requireNonNull(composition);Objects.requireNonNull(analysis);if((status==Status.CREATED)!=composition.isPresent()||composition.isPresent()&&analysis.isPresent())throw new IllegalArgumentException("Résultat de tâche incohérent");}
  public String describe(){return "Tâche : "+status+"\n"+output+"\nRequête structurée :\n"+requestJson+composition.map(c->"\nPlan de contenu (fiction) :\n"+c.plan().json()+"\nCohérence interne entre plan et grammaire contrôlée par réanalyse ; ce contrôle ne prouve pas une compréhension indépendante. La qualité poétique reste à apprécier.").orElse("")+analysis.map(a->"\nDocument copulaire (sous-lecture) :\n"+a.document().json()+a.registry().map(r->"\nRegistre des lectures validées :\n"+r.json()).orElse("")).orElse("")+webContext.map(w->"\n"+w.describe()).orElse("")+discourse.map(d->"\n"+d.describe()+"\nDiscours JSON :\n"+d.json()).orElse("")+lexical.map(t->"\n"+t.describe()).orElse("");}
 }
 private final CreativeTextEngine creative;
 private final FrenchDiscourse discourseReader=new FrenchDiscourse();
 private final TextContextAnalysis analyzer;
 private long sequence;
 private final TextWebContextService web;
 private final LexicalKnowledgeService lexicon;
 private final Map<String,Result> lexicalTasks=new LinkedHashMap<>();
 private final Map<String,DirectiveActs.Directive> requests=new LinkedHashMap<>();
 public TextTaskService(){this(new CreativeTextEngine(),632L,new WebSource.Wikipedia());}
 public TextTaskService(CreativeTextEngine creative,long seed){this(creative,seed,new WebSource.Wikipedia());}
 public TextTaskService(CreativeTextEngine creative,long seed,WebSource.Provider provider){this(creative,seed,provider,new LexicalKnowledgeService(provider));}
 public TextTaskService(CreativeTextEngine creative,long seed,WebSource.Provider provider,LexicalKnowledgeService lexicon){this.lexicon=Objects.requireNonNull(lexicon);this.creative=creative;analyzer=new TextContextAnalysis(creative.vocabulary());sequence=seed;web=new TextWebContextService(provider,creative.vocabulary(),lexicon);}
 public List<String> taskIds(){var ids=new ArrayList<>(web.taskIds());ids.addAll(lexicalTasks.keySet());return List.copyOf(ids);}
 public Result revisit(String id){if(id.startsWith("L"))return revisitLexical(id);return contextual(request(id),web.revisit(id));}
 public Result exclude(String id,java.net.URI uri){if(id.startsWith("L")){lexicon.exclude(id,uri);return revisitLexical(id);}return contextual(request(id),web.exclude(id,uri));}
 private DirectiveActs.Directive request(String id){var d=requests.get(id);if(d==null)throw new IllegalArgumentException("Tâche documentaire inconnue");return d;}
 private Result contextual(DirectiveActs.Directive d,TextWebContextService.Answer w){
  var a=w.assessed();var status=a.status().equals("RESOURCE_LIMIT")?Status.RESOURCE_LIMIT:a.status().equals("INCONSISTENT_TEXT")?Status.INCONSISTENT_ANALYSIS:a.status().equals("PARTIAL_ANALYSIS")?Status.PARTIAL_ANALYSIS:w.state()==TextWebContextService.State.PROVISIONAL_REASONING?Status.PROVISIONAL_ANALYSIS:Status.ANALYZED;
  return withDiscourse(new Result(status,a.describe(),d.json(),Optional.empty(),Optional.of(a),Optional.of(w)),a.document().sourceText());
 }
 public static Result inputLimit(DirectiveActs.Directive d){
  String source=d.sourceText();String json=StructuredJsonWriter.json(Map.of("schema","ivoc.input-limit.1","kind",d.kind().name(),"characters",source.length(),"maximum",8000,"prefix",source.substring(0,Math.min(300,source.length())),"truncated",true));
  return new Result(Status.RESOURCE_LIMIT,"Demande de "+source.length()+" caractères : limite de 8000 dépassée. Aucune tâche exécutée et aucune question logique ouverte.",json,Optional.empty(),Optional.empty());
 }
 public Result execute(DirectiveActs.Directive received){
  if(received.sourceText().length()>8000)return inputLimit(received);
  var d=DirectiveActs.read(received.json());
  if(d.mixed())return empty(Status.COMPOSITE_NEEDS_CLARIFICATION,"Plusieurs actes sont présents. Le contexte est conservé ; précisez la tâche à exécuter.",d);
  if(!d.unresolved().isEmpty())return empty(Status.NEEDS_INFORMATION,"Demande à préciser : "+d.unresolved(),d);
  if(d.kind()==DirectiveActs.Kind.CREATE){
   var request=CreativeRequest.read(CreativeRequest.parse(d.object()).json());
   Result result=create(request,d);
   return new Result(result.status(),result.output(),request.json(),result.composition(),result.analysis(),result.webContext(),result.discourse(),result.lexical());
  }
  if(d.kind()==DirectiveActs.Kind.REFORMULATE&&d.verb().equals("résumer")){
   var text=extractText(d.object());
   if(text.isEmpty())return empty(Status.NEEDS_INFORMATION,"Fournissez le texte à résumer entre guillemets, ou après « ce texte : ».",d);
   try{
    var doc=discourseReader.read(discourseReader.analyze(text.get()).json());
    var summary=ExtractiveTextSummary.summarize(doc);
    return new Result(summary.selected().isEmpty()?Status.PARTIAL_SUMMARY:Status.EXTRACTED_SUMMARY,summary.describe(),d.json(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(doc));
   }catch(FrenchNlpReader.Limit ex){return empty(Status.RESOURCE_LIMIT,ex.getMessage(),d);}
   catch(FrenchNlpReader.Unavailable ex){return empty(Status.NEEDS_INFORMATION,ex.getMessage(),d);}
  }
  if(d.kind()==DirectiveActs.Kind.ANALYZE||d.kind()==DirectiveActs.Kind.EXPLAIN){
   var definition=DefinitionIntent.term(d);if(definition.isPresent())return define(definition.orElseThrow(),d);
   Optional<String> text=extractText(d.object());
   if(text.isEmpty())return empty(Status.NEEDS_INFORMATION,"Fournissez le passage entre guillemets, ou après « ce texte : ». Son contenu sera analysé comme donnée.",d);
   var a=analyzer.analyze(text.get());
   try{var context=web.investigate(a);requests.put(context.taskId(),d);return contextual(d,context);}
   catch(TextWebContextService.Capacity ex){return withDiscourse(new Result(a.status().equals("PARTIAL_ANALYSIS")?Status.PARTIAL_ANALYSIS:Status.ANALYZED,a.describe()+"\nRecherche suspendue : "+ex.getMessage(),d.json(),Optional.empty(),Optional.of(a)),text.get());}
  }
  return empty(Status.UNSUPPORTED_TASK,"Reformulation et traduction ne sont pas encore exécutées par cette version.",d);
 }
 private Result withDiscourse(Result baseline,String text){
  try{
   var doc=discourseReader.read(discourseReader.analyze(text).json());
   return new Result(baseline.status(),baseline.output(),baseline.requestJson(),baseline.composition(),baseline.analysis(),baseline.webContext(),Optional.of(doc));
  }catch(FrenchNlpReader.Limit ex){return new Result(baseline.status(),baseline.output()+"\nLecture linguistique suspendue : "+ex.getMessage(),baseline.requestJson(),baseline.composition(),baseline.analysis(),baseline.webContext());}
  catch(FrenchNlpReader.Unavailable ex){return new Result(baseline.status(),baseline.output()+"\nLecture linguistique indisponible : "+ex.getMessage(),baseline.requestJson(),baseline.composition(),baseline.analysis(),baseline.webContext());}
 }
 private static Result empty(Status s,String text,DirectiveActs.Directive d){return new Result(s,text,d.json(),Optional.empty(),Optional.empty());}
 private Result create(CreativeRequest request,DirectiveActs.Directive d){
  if(!request.unsupported().isEmpty())return empty(Status.NEEDS_INFORMATION,"Demande non exécutée : "+request.unsupported(),d);
  Optional<String> theme=request.themes().stream().findFirst();
  if(theme.isPresent()&&creative.vocabulary().nouns().stream().noneMatch(n->n.word().equals(theme.get())))return createEnriched(request,d,theme.orElseThrow());
  CreativeTextEngine.Composition c;
  try{c=creative.compose(request.style(),theme,request.clauseCount(),sequence++);}
  catch(CreativeTextEngine.CompositionUnavailable ex){return empty(Status.NEEDS_INFORMATION,ex.getMessage(),d);}
  if(c.plan().clauses().size()!=request.clauseCount()||c.text().lines().count()!=request.clauseCount())throw new IllegalStateException("Contrainte de lignes non respectée");
  int verbs=(int)c.plan().clauses().stream().map(CreativeTextEngine.Clause::verb).distinct().count();
  int subjects=(int)c.plan().clauses().stream().map(CreativeTextEngine.Clause::subject).distinct().count();
  return new Result(Status.CREATED,"Composition fictive :\n"+c.text()+"\nNombre de lignes vérifié : "+request.clauseCount()+". Variété mesurée : "+verbs+" verbe(s), "+subjects+" sujet(s). Aucun contrôle métrique ou de rime n'est revendiqué.",d.json(),Optional.of(c),Optional.empty());
 }
 private LexicalKnowledgeService.Trace resolve(String term){var trace=lexicon.lookup(term);return trace.id().equals("LIMIT")?trace:lexicon.read(trace.json());}
 private Result lexicalResult(Status status,String output,String requestJson,Optional<CreativeTextEngine.Composition> composition,LexicalKnowledgeService.Trace trace){
  var result=new Result(status,output,requestJson,composition,Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(trace));
  if(!trace.id().equals("LIMIT"))lexicalTasks.put(trace.id(),result);return result;
 }
 private Result define(String term,DirectiveActs.Directive d){
  if(lexicalTasks.size()>=128)return empty(Status.RESOURCE_LIMIT,"Limite de 128 tâches lexicales atteinte",d);
  var trace=resolve(term);
  if(trace.entry().isEmpty())return lexicalResult(trace.state()==LexicalKnowledgeService.State.RESOURCE_LIMIT?Status.RESOURCE_LIMIT:Status.NEEDS_INFORMATION,"Recherche effectuée ; définition non sélectionnée : "+trace.detail(),d.json(),Optional.empty(),trace);
  return lexicalResult(Status.DOCUMENTED_TEXT,"Définition documentaire proposée, recomposée depuis les segments de l'article :\n"+trace.entry().orElseThrow().composedSentence()+"\nLecture conditionnelle à la source, sans ajout comme vérité logique.",d.json(),Optional.empty(),trace);
 }
 private Result createEnriched(CreativeRequest request,DirectiveActs.Directive d,String term){
  if(lexicalTasks.size()>=128)return empty(Status.RESOURCE_LIMIT,"Limite de 128 tâches lexicales atteinte",d);
  var trace=resolve(term);
  if(trace.entry().isEmpty())return lexicalResult(trace.state()==LexicalKnowledgeService.State.RESOURCE_LIMIT?Status.RESOURCE_LIMIT:Status.NEEDS_INFORMATION,"Recherche effectuée ; création non reprise : "+trace.detail(),request.json(),Optional.empty(),trace);
  var entry=trace.entry().orElseThrow();
  if(request.style()==CreativeTextEngine.Style.PLAIN){
   if(request.clauseCount()!=1)return lexicalResult(Status.NEEDS_INFORMATION,"Concept enrichi ; le cadre documentaire couvre une phrase. Les "+request.clauseCount()+" lignes demandées ne sont pas produites.",request.json(),Optional.empty(),trace);
   return lexicalResult(Status.DOCUMENTED_TEXT,"Phrase informative composée à partir des segments attestés dans la définition Wikipédia :\n"+entry.composedSentence()+"\nLes segments sont sourcés ; ce n'est pas une vérité logique certifiée.",request.json(),Optional.empty(),trace);
  }
  if(entry.noun().isEmpty())return lexicalResult(Status.NEEDS_INFORMATION,"Concept documenté ; traits grammaticaux insuffisants pour la génération. Le genre n'est pas deviné.",request.json(),Optional.empty(),trace);
  var nouns=new ArrayList<>(creative.vocabulary().nouns());nouns.add(entry.noun().orElseThrow());
  var temporary=new CreativeTextEngine(new CreativeTextEngine.Vocabulary(nouns,creative.vocabulary().verbs(),creative.vocabulary().adjectives()));
  try{
   var composition=temporary.compose(request.style(),Optional.of(entry.term()),request.clauseCount(),sequence++);
   return lexicalResult(Status.CREATED,"Composition fictive après enrichissement lexical sourcé :\n"+composition.text()+"\nLe nouveau nom est utilisé dans une fiction ; aucune assertion encyclopédique n'est déduite du poème.",request.json(),Optional.of(composition),trace);
  }catch(CreativeTextEngine.CompositionUnavailable ex){return lexicalResult(Status.NEEDS_INFORMATION,"Concept enrichi ; reprise de composition impossible : "+ex.getMessage(),request.json(),Optional.empty(),trace);}
 }
 private Result revisitLexical(String id){
  var saved=lexicalTasks.get(id);if(saved==null)throw new IllegalArgumentException("Tâche lexicale inconnue");var trace=lexicon.revisit(id);
  if(trace.entry().isEmpty())return new Result(Status.NEEDS_INFORMATION,"Résultat dépendant de la source suspendu : "+trace.detail(),saved.requestJson(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(trace));
  return new Result(saved.status(),saved.output(),saved.requestJson(),saved.composition(),saved.analysis(),saved.webContext(),saved.discourse(),Optional.of(trace));
 }
 private static Optional<String> extractText(String object){
  String text=object.strip();Matcher prefix=Pattern.compile("^(?:(?:ce|cet|cette|le|la|un|une) (?:texte|passage|paragraphe|poème|phrase|récit|extrait))\\s*:\\s*(.+)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE|Pattern.DOTALL).matcher(text);
  if(prefix.matches())text=prefix.group(1).strip();
  else if(!(text.startsWith("«")||text.startsWith("\"")||text.startsWith("“")))return Optional.empty();
  if(text.length()>=2){char a=text.charAt(0),b=text.charAt(text.length()-1);if(a=='«'&&b=='»'||a=='"'&&b=='"'||a=='“'&&b=='”')text=text.substring(1,text.length()-1).strip();}
  return text.isBlank()?Optional.empty():Optional.of(text);
 }
}
