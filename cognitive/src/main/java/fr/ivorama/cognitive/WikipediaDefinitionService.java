package fr.ivorama.cognitive;
import java.util.*;
import java.text.Normalizer;

/** Recherche réelle via Provider ; les extraits restent documentaires, sans promotion en théorème. */
public final class WikipediaDefinitionService {
 public enum State { DOCUMENT_FOUND,CANDIDATES_FOUND,NO_DOCUMENTS,WEB_FAILED,INVALID_REQUEST }
 public record Answer(String questionId,State state,String requestJson,List<WebSource.Hit> documents,String detail,Optional<LexicalKnowledgeService.Trace> lexical){
  public Answer(String questionId,State state,String requestJson,List<WebSource.Hit> documents,String detail){this(questionId,state,requestJson,documents,detail,Optional.empty());}
  public Answer{documents=List.copyOf(documents);}
  public String describe(){StringBuilder out=new StringBuilder(questionId+" | "+state+"\nJSON de recherche :\n"+requestJson+"\n"+detail);
   for(var hit:documents)out.append("\nArticle : ").append(hit.title()).append("\nSource : ").append(hit.url()).append("\nConsulté : ").append(hit.retrievedAt()).append("\nExtrait Wikipédia (document, pas preuve logique) : ").append(hit.excerpt());
   if(!documents.isEmpty())out.append("\nAucun extrait n'est ajouté comme vérité à la base logique. Contenu attribué aux contributeurs de Wikipédia ; consulter l'article et son historique pour la licence et la version.");
   lexical.ifPresent(t->out.append("\n").append(t.describe()));return out.toString();}
 }
 private final LexicalKnowledgeService lexicon;private final IntegratedQuestionCycle cycle;private final WebSource.Provider provider;private final Map<String,Answer> sessions=new LinkedHashMap<>();
 public WikipediaDefinitionService(IntegratedQuestionCycle cycle,WebSource.Provider provider){this(cycle,provider,new LexicalKnowledgeService(provider));}
 public WikipediaDefinitionService(IntegratedQuestionCycle cycle,WebSource.Provider provider,LexicalKnowledgeService lexicon){this.lexicon=Objects.requireNonNull(lexicon);this.cycle=Objects.requireNonNull(cycle);this.provider=Objects.requireNonNull(provider);}
 public boolean hasQuestion(String id){return sessions.containsKey(id);}
 public Answer revisit(String id){var result=sessions.get(id);if(result==null)throw new IllegalArgumentException("Question documentaire inconnue");return result;}
 public Answer respond(String text){return respond(text,false);}
 public Answer search(String terms){return respond(terms,true);}
 private Answer respond(String text,boolean direct){
  String id=cycle.openGeneralQuestion(text==null||text.isBlank()?"Recherche Wikipédia vide":text);String json="{}";
  WikipediaQuestion.Request request;
  try{
   var extracted=direct?WikipediaQuestion.search(text):WikipediaQuestion.parse(text);json=WikipediaQuestion.write(extracted);
   request=WikipediaQuestion.read(json);
  }catch(IllegalArgumentException ex){return remember(new Answer(id,State.INVALID_REQUEST,json,List.of(),ex.getMessage()));}
  cycle.recordStructuredResearch(id,"Requête Wikipédia réelle : "+request.terms());
  var trace=lexicon.lookup(request.terms());if(!trace.id().equals("LIMIT"))trace=lexicon.read(trace.json());
  final var lexicalTrace=trace;
  if(trace.state()==LexicalKnowledgeService.State.WEB_FAILED)return remember(new Answer(id,State.WEB_FAILED,json,List.of(),trace.detail(),Optional.of(trace)));
  List<WebSource.Hit> hits=trace.documents().stream().filter(h->!lexicalTrace.excluded().contains(h.url())).toList();
  List<WebSource.Hit> accepted=new ArrayList<>();Set<String> seen=new HashSet<>();int examined=0;
  if(hits!=null)for(var hit:hits){if(++examined>12||accepted.size()>=3)break;if(valid(hit)&&seen.add(hit.url().toString()))accepted.add(hit);}
  if(accepted.isEmpty())return remember(new Answer(id,State.NO_DOCUMENTS,json,List.of(),"Aucun article exploitable renvoyé. Absence de résultat documentaire, pas preuve d'inexistence."));
  accepted.sort(Comparator.comparingInt(h->normalize(h.title()).equals(normalize(request.terms()))?0:1));
  boolean exact=accepted.stream().anyMatch(h->normalize(h.title()).equals(normalize(request.terms())));
  String message=exact?"Un article porte le titre demandé. Voici les extraits récupérés ; ils ne constituent pas une preuve logique.":"Articles candidats : aucun titre ne correspond exactement au sujet demandé. Préciser le sens recherché ; aucune correction silencieuse de la question.";
  return remember(new Answer(id,exact?State.DOCUMENT_FOUND:State.CANDIDATES_FOUND,json,accepted,message,Optional.of(trace)));
 }
 private Answer remember(Answer a){sessions.put(a.questionId(),a);cycle.recordStructuredResearch(a.questionId(),"Résultat documentaire Wikipédia : "+a.state()+" ; aucune assertion promue");cycle.recordStructuredFinding(a.questionId(),"WIKIPEDIA_"+a.state());return a;}
 static boolean valid(WebSource.Hit h){return h!=null&&"https".equals(h.url().getScheme())&&"fr.wikipedia.org".equals(h.url().getHost())&&h.url().getPort()==-1&&h.url().getUserInfo()==null&&!h.title().isBlank()&&h.title().length()<=256&&!h.excerpt().isBlank()&&h.excerpt().length()<=1800&&h.excerpt().chars().noneMatch(c->c<32&&c!='\n'&&c!='\t')&&h.url().getFragment()==null;}
 private static String normalize(String s){return Normalizer.normalize(s,Normalizer.Form.NFC).toLowerCase(Locale.ROOT).strip().replace('’','\'').replaceAll("\\s+"," ");}
 private static String failure(Throwable error){StringJoiner out=new StringJoiner(" → ");for(int i=0;error!=null&&i<4;i++,error=error.getCause())out.add(error.getClass().getSimpleName());return out.toString();}
}
