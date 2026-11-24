package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
/** Local real-model benchmark; evaluates observable effects, never self-certifies semantics. */
public final class ValidationMain {
 public static void main(String[] args)throws IOException{
  run(args, TransformationConfigLoader.loadGlobal(TransformationConfigLoader.defaultPath()));
 }
 public static void run(String[] args, TransformationConfigLoader.GlobalConfig global)throws IOException{
  var l=global.lexical();var d=l.disco();
  var base=global.transformations();
  var analyzer=new FrenchNlpAnalyzer();var texts=new TreeMap<String,String>();var analyses=new TreeMap<String,List<SentenceAnalysis>>();
  Path validation = Path.of("validation");
  if (!Files.isDirectory(validation)) validation = Path.of("french-nlp-lite/validation");
  try(var paths=Files.list(validation.resolve(Arrays.asList(args).contains("--independent") ? "independent" : "corpus"))){for(var p:paths.filter(x->x.toString().endsWith(".txt")).sorted().toList()){String id=p.getFileName().toString();String text=Files.readString(p).strip();texts.put(id,text);analyses.put(id,analyzer.analyze(text));}}
  boolean independent=Arrays.asList(args).contains("--independent");int expected=independent?342:684;
  System.out.println("Validation IV-ORAMA : "+expected+" scenarios (25 combinations, "+texts.size()+" texts).");
  boolean uncached=Arrays.asList(args).contains("--uncached"); if(uncached)System.setProperty("ivorama.cache.disabled","true");
  if(texts.size()!=(independent?6:12))throw new IllegalStateException("Unexpected corpus size");
  Path out=Path.of("reports/validation"+(independent?"-independent":"")+(uncached?"-uncached":""));Files.createDirectories(out);int count=0;
  try(var txt=Files.newBufferedWriter(out.resolve("results.txt"));var csv=Files.newBufferedWriter(out.resolve("results.csv"));var html=Files.newBufferedWriter(out.resolve("summary.html"))){
   csv.write("text,adjectives,nouns,mode,replacements,emphasis,ms\n");html.write("<!doctype html><meta charset=utf-8><title>IV-ORAMA validation</title><h1>Validation — revue humaine requise</h1>");
   for(int a:new int[]{0,25,50,75,100})for(int n:new int[]{0,25,50,75,100}){
    SynonymProvider provider=d.enabled() ? new DiscoGroupSynonymProvider(new DiscoSettings(true,d.directory(),d.minGroupScore(),d.minMargin(),d.minRetention(),d.semanticFreedom(),a,n)) : SynonymProviderFactory.create(l,global.weights());var engine=new SentenceTransformerEngine(provider);
    for(String id:texts.keySet())for(String mode: a==50&&n==50?List.of("all","no-yoda","none","nouns","adjectives","emphasis","yoda","circumstances","commas"):List.of("all","no-yoda")){
     boolean all=mode.equals("all")||mode.equals("no-yoda");var cfg=new TransformationConfig(false,true,false,mode.equals("all")||mode.equals("yoda"),all||mode.equals("adjectives"),all||mode.equals("circumstances"),all||mode.equals("commas"),false,global.transformations().randomSeed(),all||mode.equals("nouns"),all||mode.equals("emphasis"),base.nounEmphasis(),base.directDictionary(),base.emphasisFallbackLimit(),d.enabled()?base.adjectiveSubstitutionRate():a,d.enabled()?base.nounSubstitutionRate():n,base.candidateRejectionsEnabled());
     System.out.println("START "+id+" A="+a+" N="+n+" "+mode);long start=System.nanoTime();var result=SentenceTransformerService.transformAnalyses(texts.get(id),analyses.get(id),cfg,engine);long ms=(System.nanoTime()-start)/1_000_000;
     var replacements=result.sentences().stream().flatMap(s->s.replacements().stream()).toList();long emphasis=replacements.stream().filter(r->r.reason().contains("ajouté")).count();String label=id+" A="+a+" N="+n+" "+mode;
     csv.write(id+","+a+","+n+","+mode+","+replacements.size()+","+emphasis+","+ms+"\n");txt.write(label+"\nORIGINAL: "+result.original()+"\nTRANSFORMÉ: "+result.transformed()+"\nREMPLACEMENTS: "+replacements+"\nMODES: "+result.sentences().stream().map(TransformationResult::executions).toList()+"\n\n");html.write("<details><summary>"+esc(label)+"</summary><p>"+esc(result.original())+"</p><p>"+esc(result.transformed())+"</p></details>\n");count++;System.out.println("DONE "+count+" "+ms+" ms");txt.flush();csv.flush();html.flush();
    }
    System.out.println("A="+a+" N="+n+" : "+count+" scénarios terminés");
   }
  }
  if(count!=expected)throw new IllegalStateException("Incomplete benchmark: "+count);
  System.out.println("Rapports : "+out.toAbsolutePath()+" ("+count+" scenarios)");
 }
 private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
