package fr.ivorama.cognitive;

import java.net.URI;
import java.time.*;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Lexique documentaire temporaire ; aucune promotion d'extrait en axiome. */
public final class LexicalKnowledgeService {
 public enum State { ENRICHED,AMBIGUOUS,NO_DOCUMENTS,WEB_FAILED,SOURCE_UNUSABLE,EXCLUDED,EXPIRED,RESOURCE_LIMIT }
 public record Entry(String id,String term,FrenchAgreement.Gender gender,String category,String sentence,
                     int start,int end,int subjectEnd,int predicateStart,WebSource.Hit source,Instant expiresAt){
  public boolean active(Instant now){return now.isBefore(expiresAt);}
  public Optional<CreativeTextEngine.Noun> noun(){
   if(gender==FrenchAgreement.Gender.UNKNOWN||!term.matches("[\\p{L}][\\p{L}-]*"))return Optional.empty();
   return Optional.of(new CreativeTextEngine.Noun(id,canonical(term),gender,Set.of("sourced-topic:"+id)));
  }
  public Map<String,Object> value(){return Map.of("id",id,"term",term,"gender",gender.name(),"category",category,"sentence",sentence,"span",Map.of("start",start,"end",end,"subjectEnd",subjectEnd,"predicateStart",predicateStart),"source",hitValue(source),"expiresAt",expiresAt.toString(),"epistemicStatus","DOCUMENTARY_CANDIDATE");}
  public String composedSentence(){return source.excerpt().substring(start,subjectEnd).strip()+" "+source.excerpt().substring(predicateStart,end).strip();}
  public String json(){return StructuredJsonWriter.json(value());}
 }
 public record Trace(String id,String term,State state,List<WebSource.Hit> documents,Optional<Entry> entry,
                     boolean cacheHit,Set<URI> excluded,String detail){
  public Trace{documents=List.copyOf(documents);Objects.requireNonNull(entry);excluded=Set.copyOf(excluded);}
  public String json(){return StructuredJsonWriter.json(Map.of("schema","ivoc.lexical-workflow.1","id",id,"term",term,"state",state.name(),"documents",documents.stream().map(LexicalKnowledgeService::hitValue).toList(),"entry",entry.map(Entry::value).orElse(Map.of()),"cacheHit",cacheHit,"excluded",excluded.stream().map(URI::toString).sorted().toList(),"detail",detail));}
  public String describe(){
   var out=new StringJoiner("\n");out.add("Enrichissement lexical "+id+" : "+state+" ; concept « "+term+" » ; cache="+cacheHit);out.add(detail);
   for(var d:documents)out.add((excluded.contains(d.url())?"Source retirée : ":"Source documentaire : ")+d.title()+" | "+d.url()+" | consultée "+d.retrievedAt());
   entry.ifPresent(e->out.add("Entrée locale sourcée : genre "+e.gender()+", définition proposée « "+e.category()+" », validité jusqu'à "+e.expiresAt()+". Ce n'est pas un fait logique authentifié."));
   out.add("Contenu attribué aux contributeurs de Wikipédia ; consulter la source pour son historique et sa licence.");out.add("JSON d'enrichissement :\n"+json());return out.toString();
  }
 }
 private final WebSource.Provider provider;private final Clock clock;
 private final Map<String,Trace> sessions=new LinkedHashMap<>();private final Map<String,Entry> cache=new LinkedHashMap<>();
 private final Set<URI> excluded=new HashSet<>();private int sequence;
 public LexicalKnowledgeService(WebSource.Provider provider){this(provider,Clock.systemUTC());}
 public LexicalKnowledgeService(WebSource.Provider provider,Clock clock){this.provider=Objects.requireNonNull(provider);this.clock=Objects.requireNonNull(clock);}
 public Trace lookup(String term){
  var request=WikipediaQuestion.read(WikipediaQuestion.write(WikipediaQuestion.search(term)));
  if(sessions.size()>=256)return new Trace("LIMIT",term,State.RESOURCE_LIMIT,List.of(),Optional.empty(),false,Set.of(),"Limite de 256 recherches lexicales dans cette session.");
  String id="L"+(++sequence),key=canonical(request.terms());var cached=cache.get(key);
  if(cached!=null&&cached.active(clock.instant()))return remember(current(new Trace(id,request.terms(),State.ENRICHED,List.of(cached.source()),Optional.of(cached),true,Set.of(),"Entrée sourcée réutilisée sans appel réseau.")));
  List<WebSource.Hit> received;
  try{received=provider.search(request.terms());}
  catch(RuntimeException ex){throw ex;}
  catch(InterruptedException ex){Thread.currentThread().interrupt();return remember(failed(id,term,ex));}
  catch(Exception ex){return remember(failed(id,term,ex));}
  var docs=new ArrayList<WebSource.Hit>();var seen=new HashSet<URI>();
  if(received!=null){int examined=0;for(var hit:received){if(++examined>12||docs.size()>=3)break;if(WikipediaDefinitionService.valid(hit)&&seen.add(hit.url()))docs.add(hit);}}
  var active=docs.stream().filter(d->!excluded.contains(d.url())).toList();
  if(active.isEmpty())return remember(new Trace(id,term,docs.isEmpty()?State.NO_DOCUMENTS:State.EXCLUDED,docs,Optional.empty(),false,excludedIn(docs),"Aucune source active exploitable. Aucun mot ni fait inventé."));
  var exact=active.stream().filter(d->canonical(d.title()).equals(key)).toList();
  if(exact.size()!=1)return remember(new Trace(id,term,State.AMBIGUOUS,docs,Optional.empty(),false,excludedIn(docs),"Un sens doit être précisé : aucun article exact unique sélectionnable. Aucun titre voisin choisi silencieusement."));
  var entry=extract(term,exact.getFirst());
  if(entry.isEmpty())return remember(new Trace(id,term,State.SOURCE_UNUSABLE,docs,Optional.empty(),false,excludedIn(docs),"Article exact trouvé, mais aucune définition initiale complète n'est validée par le fragment lexical. Les documents ne sont pas des règles de grammaire."));
  if(!entry.get().active(clock.instant()))return remember(new Trace(id,term,State.EXPIRED,docs,Optional.empty(),false,excludedIn(docs),"Document trop ancien pour l'enrichissement temporaire."));
  if(cache.size()>=128&&!cache.containsKey(key))return remember(new Trace(id,term,State.RESOURCE_LIMIT,docs,Optional.empty(),false,excludedIn(docs),"Limite de 128 concepts dans le cache local."));
  cache.put(key,entry.get());return remember(new Trace(id,term,State.ENRICHED,docs,entry,false,excludedIn(docs),"Concept recherché, définition et traits attestés enregistrés localement ; reprise de la tâche autorisée dans le fragment couvert."));
 }
 public Trace read(String json){
  if(json==null||json.length()>64000)throw new IllegalArgumentException("JSON lexical hors budget");Object value=MiniJson.parse(json);
  if(!(value instanceof Map<?,?> m)||!(m.get("id") instanceof String id))throw new IllegalArgumentException("Identifiant lexical requis");
  Trace expected=sessions.get(id);
  if(expected==null||!MiniJson.parse(expected.json()).equals(value))throw new IllegalArgumentException("JSON lexical falsifié ou étranger à la session");
  return current(expected);
 }
 public Trace revisit(String id){var trace=sessions.get(id);if(trace==null)throw new IllegalArgumentException("Recherche lexicale inconnue");return remember(current(trace));}
 public Trace exclude(String id,URI url){
  var trace=sessions.get(id);if(trace==null||trace.documents().stream().noneMatch(d->d.url().equals(url)))throw new IllegalArgumentException("Source absente de cette recherche lexicale");
  excluded.add(url);return revisit(id);
 }
 public void excludeSource(URI url){excluded.add(url);}
 public boolean active(WebSource.Hit hit){return !excluded.contains(hit.url())&&clock.instant().isBefore(hit.retrievedAt().plus(Duration.ofHours(24)));}
 private Trace current(Trace trace){
  State state=trace.state();Optional<Entry> entry=trace.entry();String detail=trace.detail();
  if(entry.isPresent()&&excluded.contains(entry.get().source().url())){state=State.EXCLUDED;entry=Optional.empty();detail="Source exclue : enrichissement et résultat dépendant suspendus.";}
  else if(entry.isPresent()&&!entry.get().active(clock.instant())){state=State.EXPIRED;entry=Optional.empty();detail="Entrée expirée : aucun rafraîchissement réseau lors d'une révision.";}
  return new Trace(trace.id(),trace.term(),state,trace.documents(),entry,trace.cacheHit(),excludedIn(trace.documents()),detail);
 }
 private Set<URI> excludedIn(List<WebSource.Hit> docs){var result=new HashSet<URI>();for(var d:docs)if(excluded.contains(d.url()))result.add(d.url());return Set.copyOf(result);}
 private Trace remember(Trace trace){sessions.put(trace.id(),trace);return trace;}
 private static Trace failed(String id,String term,Exception ex){return new Trace(id,term,State.WEB_FAILED,List.of(),Optional.empty(),false,Set.of(),"Accès Wikipédia échoué : "+ex.getClass().getSimpleName()+". Aucun enrichissement ni réponse inventés.");}
 private static Optional<Entry> extract(String term,WebSource.Hit source){
  var scan=DiscourseSegments.scan(source.excerpt());if(!scan.unresolved().isEmpty()||scan.spans().isEmpty())return Optional.empty();
  var span=scan.spans().getFirst();if(span.end()>=source.excerpt().length()||source.excerpt().charAt(span.end())!='.')return Optional.empty();
  String sentence=source.excerpt().substring(span.start(),span.end()+1);if(sentence.length()>700)return Optional.empty();
  String normalized=sentence.replace('’','\'');String word=canonical(term);
  var subject=Pattern.compile("^(?:(le|la|un|une)\\s+|l')?"+Pattern.quote(word)+"(?=\\s|[,(/])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(normalized);
  if(!subject.find())return Optional.empty();
  var copula=Pattern.compile("\\best\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(normalized);
  boolean found=false;String category="";int predicate=-1;
  while(copula.find())if(copula.start()>=subject.end()&&outsideParentheses(normalized,copula.start())){
   if(copula.start()>0&&normalized.charAt(copula.start()-1)=='\'')return Optional.empty();
   String prefix=normalized.substring(subject.end(),copula.start());
   if(Pattern.compile("\\b(?:qui|que|dont|si|selon|pourrait|peut)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(prefix).find())return Optional.empty();
   var complement=Pattern.compile("^\\s+(?:(?:un|une|le|la)\\s+|l')([\\p{L}][\\p{L}-]*)",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(normalized.substring(copula.end()));
   if(!complement.find())return Optional.empty();category=normalized.substring(copula.end()).strip().replaceFirst("[.]$","");predicate=copula.start();found=true;break;
  }
  if(!found)return Optional.empty();
  var gender=subject.group(1)==null?FrenchAgreement.Gender.UNKNOWN:Set.of("la","une").contains(subject.group(1).toLowerCase(Locale.ROOT))?FrenchAgreement.Gender.FEMININE:FrenchAgreement.Gender.MASCULINE;
  String id="web_"+digest(word+"\n"+source.url()+"\n"+source.retrievedAt()+"\n"+sentence);
  return Optional.of(new Entry(id,word,gender,category,sentence,span.start(),span.end()+1,span.start()+subject.end(),span.start()+predicate,source,source.retrievedAt().plus(Duration.ofHours(24))));
 }
 private static boolean outsideParentheses(String text,int end){int depth=0;boolean quoted=false;for(int i=0;i<end;i++){char c=text.charAt(i);if(c=='(')depth++;else if(c==')')depth--;else if("«»\"“”".indexOf(c)>=0)quoted=!quoted;}return depth==0&&!quoted;}
 static String canonical(String value){return Normalizer.normalize(value,Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");}
 private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0,24);}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
 private static Map<String,Object> hitValue(WebSource.Hit hit){return Map.of("url",hit.url().toString(),"title",hit.title(),"excerpt",hit.excerpt(),"retrievedAt",hit.retrievedAt().toString(),"provider",hit.provider());}
}
