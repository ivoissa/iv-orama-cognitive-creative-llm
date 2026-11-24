package com.ivorama.nlp.transform;
import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
/** Optional upstream DISCO adapter; reflection isolates the external all-in-one JAR. */
public final class DiscoWordSpace {
 private final Object model,cosine;private final Method vector,similarity,similarWords;private final int vocabulary;
 private final ClassLoader isolated;
 private record Pair(String a,String b){}
 private final BoundedCache<Pair,Double> scores=new BoundedCache<>(32768);
 public synchronized String metrics(){return "wordSimilarity "+scores.metrics();}

 private final Map<String,Map<String,Float>> cache=new LinkedHashMap<>(512,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Map<String,Float>> e){return size()>512;}};
 public DiscoWordSpace(Path path){ClassLoader prior=Thread.currentThread().getContextClassLoader();try{
  // CoreNLP uses Lucene 7; the official DISCO model requires Lucene 5.
  // Keep the upstream binary and its dependencies in a dedicated loader.
  var jar=Path.of(System.getProperty("ivorama.disco.jar","third-party/disco/disco-3.0.0-all.jar"));
  if(!Files.isRegularFile(jar)) throw new IllegalStateException("Missing DISCO JAR: "+jar.toAbsolutePath()+"; run installer.cmd on Windows or installer.sh on Linux/macOS");
  if(!Files.isRegularFile(path.resolve("disco.config"))) throw new IllegalStateException("Missing DISCO model: "+path.toAbsolutePath());
  isolated=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader());
  Thread.currentThread().setContextClassLoader(isolated);
  Class<?> api=Class.forName("de.linguatools.disco.DISCO",true,isolated),measure=Class.forName("de.linguatools.disco.DISCO$SimilarityMeasure",true,isolated);model=api.getMethod("open",String.class).invoke(null,path.toAbsolutePath().toString());vector=api.getMethod("getWordvector",String.class);similarWords=api.getMethod("similarWords",String.class);Object m=Arrays.stream(measure.getEnumConstants()).filter(x->x.toString().equals("COSINE")).findFirst().orElseThrow();cosine=api.getMethod("getVectorSimilarity",measure).invoke(null,m);similarity=Class.forName("de.linguatools.disco.VectorSimilarity",true,isolated).getMethod("computeSimilarity",Map.class,Map.class);vocabulary=(Integer)api.getMethod("numberOfWords").invoke(model);}catch(Exception e){Throwable cause=e;while(cause instanceof InvocationTargetException && cause.getCause()!=null)cause=cause.getCause();throw new IllegalStateException("DISCO unavailable: "+cause+"; verify Java17 --add-opens flags",e);}finally{Thread.currentThread().setContextClassLoader(prior);}}
 private final BoundedCache<String,Double> norms=new BoundedCache<>(4096);
 private final Map<String,List<String>> neighborCache=new LinkedHashMap<>(256,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,List<String>> e){return size()>256;}};
 public synchronized List<String> neighbors(String word,int limit){
  if(limit<=0)return List.of();
  var cached=neighborCache.get(word);if(cached!=null&&!Boolean.getBoolean("ivorama.cache.disabled"))return cached.stream().limit(limit).toList();
  try{Object result=similarWords.invoke(model,word);if(result==null)return List.of();String[] words=(String[])result.getClass().getField("words").get(result);var all=words==null?List.<String>of():Arrays.stream(words).filter(Objects::nonNull).limit(100).toList();neighborCache.put(word,all);return all.stream().limit(limit).toList();}
  catch(ReflectiveOperationException e){throw new IllegalStateException("DISCO similarWords failed for "+word,e);}
 }
 public int vocabulary(){return vocabulary;}
 @SuppressWarnings("unchecked") public synchronized Map<String,Float> vector(String word){String w=RobertGroupEvidence.norm(word);var found=cache.get(w);if(found!=null&&!Boolean.getBoolean("ivorama.cache.disabled"))return found;try{var raw=(Map<String,Float>)vector.invoke(model,w);var v=raw==null?Map.<String,Float>of():Map.copyOf(raw);cache.put(w,v);return v;}catch(Exception e){throw new IllegalStateException("DISCO lookup failed: "+w,e);}}
 public Map<String,Float> prototype(Map<String,Double> terms){var out=new HashMap<String,Float>();for(var e:terms.entrySet()){var v=vector(e.getKey());double norm=Boolean.getBoolean("ivorama.cache.disabled")?norm(v):norms.get(e.getKey(),k->norm(v));if(norm==0)continue;double scale=e.getValue()/norm;v.forEach((k,x)->out.merge(k,(float)(x*scale),Float::sum));}return out;}
 private static double norm(Map<String,Float> v){return Math.sqrt(v.values().stream().mapToDouble(x->(double)x*x).sum());}
 public Map<String,Float> prototype(Collection<String> terms){var weights=new TreeMap<String,Double>();for(String t:terms)weights.put(t,1d);return prototype(weights);}
 public double cosine(Map<String,Float> a,Map<String,Float>b){if(a.isEmpty()||b.isEmpty())return 0;try{double x=((Number)similarity.invoke(cosine,a,b)).doubleValue();return Double.isFinite(x)?Math.max(0,Math.min(1,x)):0;}catch(Exception e){throw new IllegalStateException(e);}}
 // DISCO 3 uses float accumulation in map iteration order. Retain that
 // arithmetic while preparing the unchanged right-hand norm once per context.
 static final class ContextVector {
  private final Map<String,Float> values;
  private final float squaredNorm;

  private ContextVector(Map<String,Float> values) {
   this.values = values;
   float sum = 0;
   for (float value : values.values()) sum += value * value;
   squaredNorm = sum;
  }

  boolean isEmpty() { return values.isEmpty(); }
 }

 ContextVector contextVector(Map<String,Double> terms) {
  return new ContextVector(prototype(terms));
 }

 double cosine(Map<String,Float> word, ContextVector context) {
  if (word.isEmpty() || context.isEmpty()) return 0;
  float squaredNorm = 0;
  float dot = 0;
  for (String feature : word.keySet()) {
   float value = word.get(feature);
   squaredNorm += value * value;
   Float other = context.values.get(feature);
   if (other != null) dot += value * other;
  }
  double score = dot / Math.sqrt(squaredNorm * context.squaredNorm);
  return Double.isFinite(score) ? Math.max(0, Math.min(1, score)) : 0;
 }

 public double wordSimilarity(String a,String b){return cachedSimilarity(a,b);}
 private synchronized double cachedSimilarity(String a,String b){
  if(Boolean.getBoolean("ivorama.cache.disabled"))return cosine(vector(a),vector(b));
  return scores.get(new Pair(RobertGroupEvidence.norm(a),RobertGroupEvidence.norm(b)),k->cosine(vector(k.a()),vector(k.b())));
 }
}
