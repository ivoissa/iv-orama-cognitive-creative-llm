package fr.ivorama.cognitive;
import java.util.*;
import java.time.*;
import java.net.*;
import java.nio.file.*;
import static fr.ivorama.cognitive.WikidataSource.*;
import static fr.ivorama.cognitive.JenaClassificationEngine.*;

/** Fixtures explicitement simulées ; assertions d'inférence et de refus, HTTP réel séparé. */
public final class Wikidata0637Test {
 private static int checks;
 private static void ok(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 private static Claim edge(String s,Relation r,String o){return new Claim(s+"$"+r.property+"-"+o,s,r,o,"normal","{}","[]");}
 private static Snapshot doc(String id,List<Claim> edges){return new Snapshot(id,id,1,Instant.parse("2026-10-18T00:00:00Z"),URI.create("https://www.wikidata.org/wiki/"+id),edges,List.of(),false);}
 private static void inference(){
  var engine=new JenaClassificationEngine();
  for(int n=1;n<=6;n++){
   List<Claim> edges=new ArrayList<>();edges.add(edge("Q1",Relation.INSTANCE_OF,"Q2"));for(int i=2;i<n+2;i++)edges.add(edge("Q"+i,Relation.SUBCLASS_OF,"Q"+(i+1)));
   var answer=engine.evaluate("Q1","Q"+(n+2),edges);
   ok(answer.status().equals("SUPPORTED_CONDITIONALLY"),"P31 puis chaîne P279, longueur "+n);
   ok(answer.certificates().size()==1&&answer.certificates().getFirst().conclusion().relation()==Relation.INSTANCE_OF,"Ne devient pas sous-classe");
   ok(answer.supportClaimIds().size()==edges.size(),"Toutes les feuilles utiles citées");
   Set<Fact> base=new HashSet<>();edges.forEach(c->base.add(new Fact(c.subject(),c.relation(),c.object())));
   ok(verify(answer.certificates().getFirst(),base),"Certificat validé indépendamment du calcul Jena");
  }
  var wrong=List.of(edge("Q1",Relation.INSTANCE_OF,"Q2"),edge("Q2",Relation.INSTANCE_OF,"Q3"));
  ok(engine.evaluate("Q1","Q3",wrong).status().equals("UNKNOWN"),"P31 non transitif");
  var inverse=List.of(edge("Q1",Relation.SUBCLASS_OF,"Q2"),edge("Q2",Relation.INSTANCE_OF,"Q3"));
  ok(engine.evaluate("Q1","Q3",inverse).status().equals("UNKNOWN"),"Pas d'héritage de l'instance d'une classe");
  ok(engine.evaluate("Q3","Q1",wrong).status().equals("UNKNOWN"),"Pas de symétrie ni de faux par absence");
  var cycle=List.of(edge("Q1",Relation.SUBCLASS_OF,"Q2"),edge("Q2",Relation.SUBCLASS_OF,"Q1"),edge("Q2",Relation.SUBCLASS_OF,"Q3"));
  ok(engine.evaluate("Q1","Q3",cycle).status().equals("SUPPORTED_CONDITIONALLY"),"Cycle fini, chemin avec certificat");
  var fake=new Certificate("type",new Fact("Q1",Relation.INSTANCE_OF,"Q3"),List.of(new Certificate("SOURCE",new Fact("Q1",Relation.INSTANCE_OF,"Q2"),List.of()),new Certificate("SOURCE",new Fact("Q2",Relation.INSTANCE_OF,"Q3"),List.of())));
  ok(!verify(fake,Set.of(new Fact("Q1",Relation.INSTANCE_OF,"Q2"),new Fact("Q2",Relation.INSTANCE_OF,"Q3"))),"Faux certificat de transitivité P31 refusé");
 }
 private static void differential(){
  Random random=new Random(637L);var engine=new JenaClassificationEngine();
  for(int sample=0;sample<20;sample++){
   boolean[][] sub=new boolean[7][7],type=new boolean[7][7];List<Claim> edges=new ArrayList<>();
   for(int a=0;a<7;a++)for(int b=0;b<7;b++)if(a!=b){if(random.nextInt(10)==0){sub[a][b]=true;edges.add(edge("Q"+(a+1),Relation.SUBCLASS_OF,"Q"+(b+1)));}if(random.nextInt(15)==0){type[a][b]=true;edges.add(edge("Q"+(a+1),Relation.INSTANCE_OF,"Q"+(b+1)));}}
   // Oracle de test par fermeture matricielle ; distinct des règles et certificats Jena.
   for(int k=0;k<7;k++)for(int a=0;a<7;a++)for(int b=0;b<7;b++)sub[a][b]|=sub[a][k]&&sub[k][b];
   for(int pair=0;pair<12;pair++){
    int a=random.nextInt(7),b=random.nextInt(7);boolean instance=type[a][b];for(int c=0;c<7;c++)instance|=type[a][c]&&sub[c][b];
    var answer=engine.evaluate("Q"+(a+1),"Q"+(b+1),edges);
    ok(answer.certificates().stream().anyMatch(c->c.conclusion().relation()==Relation.INSTANCE_OF)==instance,"Oracle indépendant : instance, sans transitivité P31");
    ok(answer.certificates().stream().anyMatch(c->c.conclusion().relation()==Relation.SUBCLASS_OF)==sub[a][b],"Oracle indépendant : sous-classe");
   }
  }
 }
 private static void sessions(){
  var request=new WikidataClassificationService.Request("Terme ambigu","Q3");ok(WikidataClassificationService.Request.read(request.json()).equals(request),"Contrat JSON de classification effectivement relu");
  boolean invalid=false;try{WikidataClassificationService.Request.read(request.json().replace("ivoc.wikidata-classification.1","other"));}catch(IllegalArgumentException ex){invalid=true;}ok(invalid,"Contrat de classification falsifié refusé");

  int[] calls={0};var source=new WikidataSource(){public List<Candidate> search(String s){calls[0]++;return List.of(new Candidate("Q1","homonyme A","SIMULATED"),new Candidate("Q9","homonyme B","SIMULATED"));}public Snapshot load(String id){calls[0]++;return switch(id){case "Q1"->doc(id,List.of(edge(id,Relation.INSTANCE_OF,"Q2")));case "Q2"->doc(id,List.of(edge(id,Relation.SUBCLASS_OF,"Q3")));default->doc(id,List.of());};}};
  var cycle=new IntegratedQuestionCycle();var service=new WikidataClassificationService(cycle,source);
  ok(service.classify("mot ambigu","Q3").state().equals("NEEDS_CLARIFICATION"),"Aucun choix caché d'homonyme");
  var a=service.classify("Q1","Q3");ok(a.state().equals("SUPPORTED_CONDITIONALLY"),"Chargement dynamique puis Jena");int requests=calls[0];
  ok(service.revisit(a.questionId()).state().equals("SUPPORTED_CONDITIONALLY")&&calls[0]==requests,"Révision sans HTTP");
  var after=service.exclude(a.questionId(),"Q2$P279-Q3");ok(after.state().equals("UNKNOWN")&&after.reasoning().certificates().isEmpty()&&calls[0]==requests,"Retrait de source retire la preuve");
  ok(service.revisit(a.questionId()).reasoning().certificates().isEmpty(),"Exclusion persistante en session");
  ok(cycle.questionActCount()==2&&cycle.premises().isEmpty(),"Même Q en révision, aucun fait global ajouté");
  var failing=new WikidataClassificationService(new IntegratedQuestionCycle(),new WikidataSource(){public List<Candidate> search(String s)throws Exception{throw new java.io.IOException();}public Snapshot load(String id)throws Exception{throw new java.io.IOException();}});
  ok(failing.classify("Q1","Q3").state().equals("WEB_FAILED"),"Panne ≠ inconnu logique ou faux");
  var limited=new WikidataClassificationService(new IntegratedQuestionCycle(),new WikidataSource(){public List<Candidate> search(String s){return List.of();}public Snapshot load(String id){int n=Integer.parseInt(id.substring(1));return doc(id,List.of(edge(id,Relation.SUBCLASS_OF,"Q"+(n+1))));}});
  ok(limited.classify("Q1","Q20").state().equals("RESOURCE_LIMIT"),"Profondeur limitée explicitement");
  var broad=new WikidataClassificationService(new IntegratedQuestionCycle(),new WikidataSource(){public List<Candidate> search(String s){return List.of();}public Snapshot load(String id){List<Claim> edges=new ArrayList<>();int n=Integer.parseInt(id.substring(1));for(int i=0;i<64;i++)edges.add(edge(id,Relation.SUBCLASS_OF,"Q"+(n==1?i+2:1000+n*64+i)));return doc(id,edges);}});
  ok(broad.classify("Q1","Q999999").state().equals("RESOURCE_LIMIT"),"Budget d'identifiants vérifié avant l'appel Jena, pas de panne interne");
  var preferred=new Claim("Q1$p","Q1",Relation.INSTANCE_OF,"Q3","preferred","{\"P1\":[]}","[]");
  ok(doc("Q1",List.of(edge("Q1",Relation.INSTANCE_OF,"Q2"),preferred)).usable().isEmpty(),"Préféré qualifié : pas de remplacement par normal opportuniste");
 }
 private static void parsing()throws Exception{
  Path cache=Files.createTempDirectory("wd-fixture-");try{
   var http=new WikidataHttpSource(cache,true);
   String payload="{\"entities\":{\"Q1\":{\"id\":\"Q1\",\"lastrevid\":123,\"labels\":{\"fr\":{\"value\":\"SIMULATED\"}},\"claims\":{\"P31\":[{\"id\":\"Q1$a\",\"rank\":\"normal\",\"mainsnak\":{\"property\":\"P31\",\"snaktype\":\"value\",\"datatype\":\"wikibase-item\",\"datavalue\":{\"value\":{\"entity-type\":\"item\",\"id\":\"Q2\"}}},\"references\":[{\"source\":\"SIMULATED\"}]}]}}}}";
   var lower=http.parse("Q1",payload.replace("Q1$a","q1$a"),Instant.now(),false);ok(lower.usable().size()==1&&lower.claims().getFirst().id().equals("q1$a"),"Préfixe de déclaration insensible à la casse, identifiant original conservé");
   var d=http.parse("Q1",payload,Instant.now(),false);ok(d.revision()==123&&d.usable().size()==1&&d.claims().getFirst().referencesJson().contains("SIMULATED"),"Révision, type et références conservés");
   var mapper=new com.fasterxml.jackson.databind.ObjectMapper();Files.writeString(cache.resolve("Q1.json"),mapper.writeValueAsString(Map.of("retrievedAt",Instant.now().toString(),"payload",payload)));
   ok(http.load("Q1").cached(),"Cache relu en mode hors ligne");
   var qualified=http.parse("Q1",payload.replace("\"references\":[", "\"qualifiers\":{\"P580\":[]},\"references\":["),Instant.now(),false);ok(qualified.usable().isEmpty()&&!qualified.warnings().isEmpty(),"Qualificatifs conservés mais non aplatis en vérité inconditionnelle");
   var deprecated=http.parse("Q1",payload.replace("\"normal\"","\"deprecated\""),Instant.now(),false);ok(deprecated.usable().isEmpty(),"Rang déprécié exclu");
   Files.writeString(cache.resolve("Q1.json"),mapper.writeValueAsString(Map.of("retrievedAt",Instant.now().minus(Duration.ofDays(2)).toString(),"payload",payload)));
   boolean rejected=false;try{http.load("Q1");}catch(java.io.IOException ex){rejected=ex.getMessage().contains("CACHE_EXPIRED");}ok(rejected,"Cache expiré non présenté comme frais");
   rejected=false;try{http.load("../../secret");}catch(IllegalArgumentException ex){rejected=true;}ok(rejected,"Identifiant validé avant tout accès fichier");
   rejected=false;try{http.parse("Q9",payload,Instant.now(),false);}catch(java.io.IOException ex){rejected=true;}ok(rejected,"Document d'une autre entité refusé");
  }finally{try(var files=Files.walk(cache)){for(var f:files.sorted(Comparator.reverseOrder()).toList())Files.delete(f);}}
 }
 public static void main(String[] args)throws Exception{inference();differential();sessions();parsing();System.out.println("Wikidata0637Test OK : "+checks+" contrôles, fournisseurs SIMULÉS ; Jena réel");}
}
