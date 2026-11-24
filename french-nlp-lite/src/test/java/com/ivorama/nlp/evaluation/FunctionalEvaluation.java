package com.ivorama.nlp.evaluation;
import com.ivorama.nlp.core.*;import com.ivorama.nlp.transform.*;import java.nio.file.*;import java.util.*;import java.lang.reflect.*;import javax.json.*;
/** Real CoreNLP transformations. Same compiled harness can run against original RC6.21. */
public final class FunctionalEvaluation {
 public static JsonValue json(Object v)throws Exception{
  if(v==null)return JsonValue.NULL;if(v instanceof String||v instanceof Path||v instanceof Enum<?>)return Json.createValue(v.toString());if(v instanceof Boolean b)return b?JsonValue.TRUE:JsonValue.FALSE;
  if(v instanceof Number n)return Json.createValue(new java.math.BigDecimal(n.toString()));
  if(v instanceof Map<?,?> m){var b=Json.createObjectBuilder();for(var e:m.entrySet())b.add(e.getKey().toString(),json(e.getValue()));return b.build();}
  if(v instanceof Iterable<?> list){var b=Json.createArrayBuilder();for(var x:list)b.add(json(x));return b.build();}
  if(v.getClass().isRecord()){var b=Json.createObjectBuilder();for(var c:v.getClass().getRecordComponents()){var a=c.getAccessor();a.trySetAccessible();b.add(c.getName(),json(a.invoke(v)));}return b.build();}
  return Json.createValue(v.toString());
 }
 public static void main(String[] args)throws Exception{
  Path yaml=Path.of(args[0]),input=Path.of(args[1]),output=Path.of(args[2]);String label=args.length>3?args[3]:"run";
  long started=System.nanoTime();var analyzer=new FrenchNlpAnalyzer();double analyzerMs=(System.nanoTime()-started)/1e6;
  started=System.nanoTime();SynonymProvider provider;try{provider=(SynonymProvider)SynonymProviderFactory.class.getMethod("create",Path.class).invoke(null,yaml);}catch(NoSuchMethodException old){provider=SynonymProviderFactory.create(TransformationConfigLoader.loadLexical(yaml));}double providerMs=(System.nanoTime()-started)/1e6;
  var config=TransformationConfigLoader.load(yaml);var service=new SentenceTransformerService(analyzer,provider);var rows=new ArrayList<Object>();var times=new ArrayList<Double>();
  service.transformText("Le visiteur observe le jardin.",config);
  List<String> inputs;if(input.toString().endsWith(".json")){try(var reader=Json.createReader(Files.newBufferedReader(input))){inputs=List.of("HISTORY\t"+reader.readObject().getJsonObject("result").getString("original"));}}else inputs=Files.readAllLines(input);
  for(String line:inputs){if(line.isBlank()||line.startsWith("id\t"))continue;String[] f=line.split("\t",2);started=System.nanoTime();var r=service.transformText(f[1],config);double ms=(System.nanoTime()-started)/1e6;times.add(ms);var analyses=analyzer.analyze(f[1]);
   var targets=new ArrayList<Object>();for(var analysis:analyses){var selected=new ArrayList<TokenData>();var nouns=analysis.tokens().stream().filter(TokenData::isNoun).filter(t->!Set.of("PROPN","NPP").contains(t.pos())).toList();if(config.replaceNounsEnabled()){if(!nouns.isEmpty())selected.add(nouns.get(0));if(nouns.size()>=3)selected.add(nouns.get(2));}if(config.emphaseColores()){var adjs=new ArrayList<>(analysis.tokens().stream().filter(TokenData::isAdjective).toList());if(adjs.size()>3){Collections.shuffle(adjs,new Random(config.randomSeed()));adjs=new ArrayList<>(adjs.subList(0,(adjs.size()+1)/2));}selected.addAll(adjs);}for(var target:selected){List<String> inventory;try{inventory=(List<String>)SynonymProvider.class.getMethod("inventory",TokenData.class,SynonymRegister.class,SentenceAnalysis.class).invoke(provider,target,r.sentences().get(analyses.indexOf(analysis)).effectiveRegister(),analysis);}catch(NoSuchMethodException old){inventory=provider.candidates(target,SynonymRegister.COURANT,analysis);}targets.add(Map.of("sentence",analysis.originalText(),"token",target.index(),"word",target.word(),"pos",target.pos(),"availableCandidates",inventory));}}
   rows.add(Map.of("id",f[0],"result",r,"analysis",analyses,"targets",targets,"transformMilliseconds",ms));System.out.println(f[0]+" "+ms+"ms "+r.transformed());
  }
  Files.createDirectories(output.toAbsolutePath().getParent());var result=Map.of("label",label,"java",System.getProperty("java.version"),"yaml",Files.readString(yaml),"analyzerInitializationMs",analyzerMs,"providerInitializationMs",providerMs,"rows",rows,"latenciesMs",times,"networkRequests",0);
  Files.writeString(output,json(result).toString());
 }
}
