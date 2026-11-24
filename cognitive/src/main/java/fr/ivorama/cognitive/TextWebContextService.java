package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;
import java.net.URI;
import java.text.Normalizer;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Documents Wikipédia -> hypothèses typées dans le vocabulaire du texte -> réévaluation locale. */
public final class TextWebContextService {
 public static final class Capacity extends RuntimeException{public Capacity(String message){super(message);}}
 public enum State { LOCAL_ONLY,DOCUMENTS_ONLY,NO_DOCUMENTS,WEB_FAILED,PROVISIONAL_REASONING }
 public record Term(String text,int start,int end){ }
 public record Proposal(Premise premise,WebSource.Hit document,String subject,String category){ }
 public record Answer(String taskId,State state,TextContextAnalysis.Result baseline,TextContextAnalysis.Result assessed,
                      List<Term> terms,List<WebSource.Hit> documents,List<Proposal> proposals,List<String> errors,Set<URI> excluded,List<LexicalKnowledgeService.Trace> lexical){
  public Answer(String taskId,State state,TextContextAnalysis.Result baseline,TextContextAnalysis.Result assessed,List<Term> terms,List<WebSource.Hit> documents,List<Proposal> proposals,List<String> errors,Set<URI> excluded){this(taskId,state,baseline,assessed,terms,documents,proposals,errors,excluded,List.of());}
  public Answer{lexical=List.copyOf(lexical);terms=List.copyOf(terms);documents=List.copyOf(documents);proposals=List.copyOf(proposals);errors=List.copyOf(errors);excluded=Set.copyOf(excluded);}
  public String describe(){
   StringJoiner out=new StringJoiner("\n");out.add("Contexte Wikipédia "+taskId+" : "+state);
   for(var t:terms)out.add("Recherche lexicale : « "+t.text()+" » ["+t.start()+":"+t.end()+"]");
   for(var d:documents)out.add((excluded.contains(d.url())?"Source EXCLUE : ":"Document : ")+d.title()+" | fournisseur "+d.provider()+" | "+d.url()+" | consulté "+d.retrievedAt()+"\nExtrait documentaire : "+d.excerpt());
   for(var p:proposals)out.add("Hypothèse NON VÉRIFIÉE "+p.premise().id()+" : « "+p.subject()+" » classé « "+p.category()+" » ; source "+p.document().url());
   if(!proposals.isEmpty()&&baseline.formallyComplete())out.add("Le raisonnement repris est conditionnel aux hypothèses documentaires ; aucune vérité permanente n'est ajoutée.");
   else if(!documents.isEmpty())out.add("Retour à l'analyse : documents consultables, aucune assertion suffisamment liée au vocabulaire du texte pour produire une nouvelle preuve.");
   if(!baseline.formallyComplete())out.add("La recherche ne résout pas automatiquement la syntaxe ou l'image littéraire non comprise.");
   for(var trace:lexical)out.add(trace.describe());
   for(var e:errors)out.add("Recherche : "+e);
   return out.toString();
  }
 }
 private record Session(TextContextAnalysis.Result baseline,List<Term> terms,List<WebSource.Hit> documents,List<Proposal> proposals,List<String> errors,Set<URI> excluded,List<LexicalKnowledgeService.Trace> lexical){}
 private final LexicalKnowledgeService lexicon;private final TextContextAnalysis analyzer;private final Set<String> known;
 private final Map<String,Session> sessions=new LinkedHashMap<>();private int sequence;
 public TextWebContextService(WebSource.Provider provider,CreativeTextEngine.Vocabulary vocabulary){this(provider,vocabulary,new LexicalKnowledgeService(provider));}
 public TextWebContextService(WebSource.Provider provider,CreativeTextEngine.Vocabulary vocabulary,LexicalKnowledgeService lexicon){Objects.requireNonNull(provider);this.lexicon=Objects.requireNonNull(lexicon);analyzer=new TextContextAnalysis(vocabulary);known=new HashSet<>();vocabulary.nouns().forEach(n->known.add(normalize(n.word())));}
 public Answer investigate(TextContextAnalysis.Result local){
  if(sessions.size()>=64)throw new Capacity("Limite de 64 tâches documentaires en mémoire atteinte");
  String id="T"+(++sequence);List<Term> terms=plan(local.document(),known);List<WebSource.Hit> docs=new ArrayList<>();List<Proposal> proposals=new ArrayList<>();List<String> errors=new ArrayList<>();Set<String> seen=new HashSet<>();List<LexicalKnowledgeService.Trace> lexical=new ArrayList<>();
  for(var term:terms){
   var trace=lexicon.lookup(term.text());
   if(!trace.id().equals("LIMIT"))trace=lexicon.read(trace.json());lexical.add(trace);
   if(trace.state()==LexicalKnowledgeService.State.WEB_FAILED)errors.add(term.text()+" : "+trace.detail());
   final var acceptedTrace=trace;List<WebSource.Hit> hits=trace.documents().stream().filter(h->!acceptedTrace.excluded().contains(h.url())).toList();
   int inspected=0;for(var hit:hits){
    if(++inspected>12||docs.size()>=6)break;
    if(!valid(hit)||!seen.add(hit.url()+"\n"+hit.excerpt()))continue;docs.add(hit);
    var p=bind(local.document(),term,hit,"web_context_"+(proposals.size()+1));p.ifPresent(proposals::add);
   }
  }
  Session s=new Session(local,terms,docs,proposals,errors,Set.of(),List.copyOf(lexical));sessions.put(id,s);return assess(id,s);
 }
 public Answer revisit(String id){return assess(id,session(id));}
 public Answer exclude(String id,URI url){var s=session(id);if(s.documents().stream().noneMatch(d->d.url().equals(url)))throw new IllegalArgumentException("Source absente de cette tâche");var excluded=new HashSet<>(s.excluded());excluded.add(url);lexicon.excludeSource(url);var next=new Session(s.baseline(),s.terms(),s.documents(),s.proposals(),s.errors(),Set.copyOf(excluded),s.lexical());sessions.put(id,next);return assess(id,next);}
 public List<String> taskIds(){return List.copyOf(sessions.keySet());}
 private Session session(String id){var s=sessions.get(id);if(s==null)throw new IllegalArgumentException("Tâche documentaire inconnue");return s;}
 private Answer assess(String id,Session s){
  var active=s.proposals().stream().filter(p->!s.excluded().contains(p.document().url())&&lexicon.active(p.document())).toList();
  var result=active.isEmpty()?s.baseline():analyzer.reassess(s.baseline(),active.stream().map(Proposal::premise).toList());
  var lexical=s.lexical().stream().map(t->t.id().equals("LIMIT")?t:lexicon.revisit(t.id())).toList();
  var observations=new ArrayList<>(result.observations());
  for(var trace:lexical)if(trace.entry().isPresent()){
   var entry=trace.entry().orElseThrow();
   for(var term:s.terms())if(LexicalKnowledgeService.canonical(term.text()).equals(LexicalKnowledgeService.canonical(trace.term())))
    observations.add(new TextContextAnalysis.Observation("CONTEXTE LEXICAL SOURCÉ",entry.composedSentence()+" Source : "+entry.source().url()+" ; consultée "+entry.source().retrievedAt()+". Définition documentaire, sans preuve supplémentaire de l'énoncé analysé.",term.start(),term.end(),result.document().sourceText().substring(term.start(),term.end())));
  }
  result=new TextContextAnalysis.Result(result.document(),observations,result.inferences(),result.status(),result.registry());
  State state=!active.isEmpty()&&s.baseline().formallyComplete()?State.PROVISIONAL_REASONING:s.terms().isEmpty()?State.LOCAL_ONLY:!s.documents().isEmpty()?State.DOCUMENTS_ONLY:!s.errors().isEmpty()?State.WEB_FAILED:State.NO_DOCUMENTS;
  return new Answer(id,state,s.baseline(),result,s.terms(),s.documents(),active,s.errors(),s.excluded(),lexical);
 }
 private static final Set<String> META=Set.of("texte","passage","phrase","poème","récit","mot","question","réponse","paragraphe");
 private static final Pattern NOMINAL=Pattern.compile("(?iu)(?:(?<![\\p{L}])(?:le|la|les|un|une|des|du|son|sa|ses)\\s+|(?<![\\p{L}])l['’])([\\p{L}][\\p{L}-]*)(?:\\s+([a-z][a-z0-9]*))?");
 static List<Term> plan(FrenchStructuredTranslator.Document d,Set<String> known){
  List<Term> result=new ArrayList<>();Set<String> seen=new HashSet<>();Set<String> declaredClasses=new HashSet<>();
  d.symbols().values().stream().filter(s->s.startsWith("class:")).forEach(s->declaredClasses.add(normalize(s.substring(6))));
  Matcher m=NOMINAL.matcher(d.sourceText());
  while(m.find()&&result.size()<2){
   String term=d.sourceText().substring(m.start(1),m.end(1));String normalized=normalize(term);
   String suffix=m.group(2);if(suffix!=null&&(suffix.length()==1||suffix.matches(".*[0-9].*")))continue;
   if(term.length()>80||known.contains(normalized)||META.contains(normalized)||declaredClasses.contains(normalized)||!seen.add(normalized))continue;
   result.add(new Term(term,m.start(1),m.end(1)));
  }
  return List.copyOf(result);
 }
 static boolean valid(WebSource.Hit h){return h!=null&&"https".equals(h.url().getScheme())&&"fr.wikipedia.org".equals(h.url().getHost())&&h.url().getPort()==-1&&h.url().getUserInfo()==null&&!h.title().isBlank()&&!h.excerpt().isBlank()&&h.title().length()<=180&&h.excerpt().length()<=2000;}
 private static Optional<Proposal> bind(FrenchStructuredTranslator.Document d,Term term,WebSource.Hit hit,String id){
  if(!normalize(hit.title()).equals(normalize(term.text())))return Optional.empty(); // titre voisin : pas de sélection de sens cachée
  var ranges=DiscourseSegments.scan(hit.excerpt());if(!ranges.unresolved().isEmpty()||ranges.spans().isEmpty())return Optional.empty();
  var first=ranges.spans().get(0);String sentence=hit.excerpt().substring(first.start(),first.end()).strip();
  if(first.end()>=hit.excerpt().length()||hit.excerpt().charAt(first.end())!='.')return Optional.empty(); // pas d'extrait tronqué admis
  if(sentence.matches("(?is).*(?:[«»\"“”]|\\b(?:pas|peut|pourrait|serait|selon|supposé|parfois)\\b).*$"))return Optional.empty();
  String flat=sentence.replace('’','\'').replaceAll("\\s+"," ");
  Matcher definition=Pattern.compile("^(?:(?:le|la) |l')?"+Pattern.quote(term.text())+"(?: \\([^)]{0,240}\\))? est (?:(?:un|une|le|la) |l')([\\p{L}][\\p{L}-]*)(?: .*)?$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(flat);
  if(!definition.matches())return Optional.empty();String category=definition.group(1);
  List<String> predicateIds=d.symbols().entrySet().stream().filter(e->e.getKey().startsWith("predicate:")&&normalize(e.getValue()).equals("class:"+normalize(category))).map(e->e.getKey().substring(10)).toList();
  var entities=d.entities().stream().filter(e->normalize(bare(d.symbols().get("entity:"+e.name()))).equals(normalize(term.text()))).toList();
  if(predicateIds.size()!=1||entities.size()!=1)return Optional.empty();
  var expression=new Atom(predicateIds.get(0),List.of(new Constant(entities.get(0))));
  var premise=new Premise(id,expression,new Evidence(Origin.WEB_EXCERPT_UNVERIFIED,hit.url()+" ; "+hit.retrievedAt(),sentence));
  return Optional.of(new Proposal(premise,hit,term.text(),category));
 }
 private static String bare(String source){return source==null?"":source.replaceFirst("(?iu)^(?:(?:le|la|les|un|une) |l['’])","");}
 private static String normalize(String s){return Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT).replace('’','\''),Normalizer.Form.NFC);}
}
