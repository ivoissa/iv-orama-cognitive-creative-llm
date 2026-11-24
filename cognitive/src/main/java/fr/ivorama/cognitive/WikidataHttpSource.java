package fr.ivorama.cognitive;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.io.*;

/** API Action Wikidata : recherche d'identifiants puis déclarations complètes, cache révisable 24h. */
public final class WikidataHttpSource implements WikidataSource {
 private static final int MAX_BYTES=2*1024*1024;
 private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(MAX_BYTES).build()).build());
 private final HttpClient http=WebHttpClientFactory.create(System.getenv());
 private final Path cache;private final boolean offline;
 public WikidataHttpSource(Path cache,boolean offline){this.cache=cache.toAbsolutePath().normalize();this.offline=offline;}
 public List<Candidate> search(String label)throws Exception{
  if(label==null||label.isBlank()||label.length()>120)throw new IllegalArgumentException("Terme Wikidata requis, au plus 120 caractères");
  if(offline)throw new IOException("OFFLINE : fournir des identifiants Q pour consulter le cache");
  var root=json.readTree(get("action=wbsearchentities&language=fr&uselang=fr&type=item&limit=5&search="+encode(label)));
  if(!root.path("search").isArray())throw new IOException("Réponse de recherche Wikidata invalide");
  List<Candidate> out=new ArrayList<>();for(var n:root.path("search")){if(out.size()==5)break;out.add(new Candidate(n.path("id").asText(),n.path("label").asText(),n.path("description").asText()));}return List.copyOf(out);
 }
 public Snapshot load(String identifier)throws Exception{
  String id=WikidataSource.qid(identifier);Path file=cache.resolve(id+".json");
  if(Files.exists(file)){
   if(Files.size(file)>MAX_BYTES*2L)throw new IOException("Cache Wikidata hors budget");
   var saved=json.readTree(Files.readString(file));Instant time=Instant.parse(saved.path("retrievedAt").asText());
   Duration age=Duration.between(time,Instant.now());if(!age.isNegative()&&age.compareTo(Duration.ofHours(24))<=0)return parse(id,saved.path("payload").asText(),time,true);
   if(offline)throw new IOException("CACHE_EXPIRED : actualisation réseau requise");
  }
  if(offline)throw new IOException("CACHE_MISS : aucune donnée hors ligne pour "+id);
  String body=get("action=wbgetentities&ids="+id+"&props=info|labels|claims&languages=fr|en");Instant now=Instant.now();Snapshot snapshot=parse(id,body,now,false);
  Files.createDirectories(cache);Path temp=Files.createTempFile(cache,"wd-",".tmp");
  try{Files.writeString(temp,json.writeValueAsString(Map.of("retrievedAt",now.toString(),"payload",body)));try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException ex){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp);}
  return snapshot;
 }
 Snapshot parse(String id,String body,Instant time,boolean cached)throws IOException{
  if(body.length()>MAX_BYTES)throw new IOException("Réponse Wikidata trop grande");
  var root=json.readTree(body);if(root.has("error"))throw new IOException("Erreur API Wikidata");var n=root.path("entities").path(id);
  if(!n.path("id").asText().equals(id)||n.has("missing")||n.path("lastrevid").asLong()<1)throw new IOException("Entité ou révision Wikidata absente");
  String label=n.path("labels").path("fr").path("value").asText(n.path("labels").path("en").path("value").asText(id));
  List<Claim> claims=new ArrayList<>();List<String> warnings=new ArrayList<>();
  for(var relation:Relation.values()){
   var entries=n.path("claims").path(relation.property);if(entries.isMissingNode())continue;if(!entries.isArray())throw new IOException("Déclarations Wikidata invalides");
   // On refuse tout le lot au-delà du budget : pas de perte silencieuse d'un rang préféré.
   if(entries.size()+claims.size()>64)throw new IOException("Budget de 64 déclarations par entité dépassé");
   for(var c:entries){var snak=c.path("mainsnak");if(!snak.path("property").asText().equals(relation.property))throw new IOException("Propriété Wikidata incohérente");
    if(!snak.path("snaktype").asText().equals("value")){if(c.path("rank").asText().equals("preferred"))throw new IOException("Valeur préférée non concrète : propriété non exploitable");warnings.add("Déclaration non concrète non inférée : "+c.path("id").asText());continue;}
    var value=snak.path("datavalue").path("value");if(!snak.path("datatype").asText().equals("wikibase-item")||!value.path("entity-type").asText().equals("item"))throw new IOException("Valeur de classification non typée");
    var qualifiers=c.path("qualifiers");var refs=c.path("references");if(!qualifiers.isMissingNode()&&!qualifiers.isObject()||!refs.isMissingNode()&&!refs.isArray())throw new IOException("Provenance Wikidata invalide");
    Claim claim;try{claim=new Claim(c.path("id").asText(),id,relation,value.path("id").asText(),c.path("rank").asText(),qualifiers.isMissingNode()?"{}":qualifiers.toString(),refs.isMissingNode()?"[]":refs.toString());}catch(IllegalArgumentException ex){throw new IOException("Déclaration Wikidata incohérente",ex);}
    claims.add(claim);if(!claim.unqualified()||claim.rank().equals("deprecated"))warnings.add("Déclaration conservée mais exclue de l'inférence : "+claim.id());
   }
  }
  return new Snapshot(id,label,n.path("lastrevid").asLong(),time,URI.create("https://www.wikidata.org/wiki/"+id),claims,warnings,cached);
 }
 private String get(String query)throws Exception{
  URI url=URI.create("https://www.wikidata.org/w/api.php?format=json&"+query.replace("|","%7C"));
  var req=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(12)).header("User-Agent","IVOramaCognitive/0.6.37 (research; https://github.com/ivoissa/iv-orama-cognitive)").header("Accept","application/json").GET().build();
  var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());try(var in=response.body()){
   if(response.statusCode()!=200)throw new IOException("Wikidata HTTP "+response.statusCode());byte[] bytes;var reader=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
   var future=reader.submit(()->in.readNBytes(MAX_BYTES+1));
   try{bytes=future.get(12,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException ex){future.cancel(true);throw new IOException("Délai de lecture Wikidata dépassé",ex);}finally{in.close();reader.shutdownNow();}
   if(bytes.length>MAX_BYTES)throw new IOException("Wikidata hors budget de réponse");String body=new String(bytes,StandardCharsets.UTF_8);if(json.readTree(body).has("error"))throw new IOException("Erreur API Wikidata");return body;
  }
 }
 private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
