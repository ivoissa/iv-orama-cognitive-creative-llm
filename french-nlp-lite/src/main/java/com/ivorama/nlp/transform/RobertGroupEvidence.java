package com.ivorama.nlp.transform;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.regex.Pattern;import javax.xml.stream.*;
/** Typed bounded lexical graph. Its neighbors supply evidence, never substitution candidates. */
public final class RobertGroupEvidence {
 private final Map<String,Set<String>> ids=new HashMap<>(),words=new HashMap<>(),gloss=new HashMap<>(),bridge=new HashMap<>(),frenchDefinitions=new HashMap<>();
 private final Map<String,Map<String,Set<String>>> relations=new HashMap<>();
 private final Map<String,Set<String>> manuallyReviewed=new HashMap<>(),manuallyRejected=new HashMap<>();
 private final Map<String,Set<String>> highConfidenceLiterals=new HashMap<>();
 private static final Set<String> STOP=Set.of("the","and","for","with","that","from","into","are","was","were","has","have","had","not","one","his","her","you","your","which","who","when","where","can","may","all","any","other","but","also","being","been","used","something","someone","their","they","them","there","more","very","what","will","would","could","should","about","some","its","act","thing");
 private static final Pattern TOKEN=Pattern.compile("[^\\p{L}]+");
 static String norm(String s){return java.text.Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT),java.text.Normalizer.Form.NFC);}
 private static void add(Map<String,Set<String>> m,String k,String v){m.computeIfAbsent(k,x->new HashSet<>()).add(v);}
 private void member(String id,String word){String w=norm(word);if(w.isBlank()||w.equals("_empty_"))return;add(ids,w,id);add(words,id,w);}
 private void edge(String from,String type,String to){relations.computeIfAbsent(from,k->new HashMap<>()).computeIfAbsent(type,k->new HashSet<>()).add(to);}
 private static InputStream resource(String name)throws IOException{var in=RobertGroupEvidence.class.getResourceAsStream("/lexicon/"+name);if(in==null)throw new FileNotFoundException(name);return in;}
 private interface Row{void accept(String[] f);}
 private static void rows(String name,Row action)throws IOException{try(var r=new BufferedReader(new InputStreamReader(resource(name),StandardCharsets.UTF_8))){for(String s;(s=r.readLine())!=null;)action.accept(s.split("\t",-1));}}
 private static final class Holder{static final RobertGroupEvidence VALUE=create();}
 public static RobertGroupEvidence load(){return Holder.VALUE;}
 private static RobertGroupEvidence create(){try{return readResources();}catch(Exception e){throw new IllegalStateException("Lexical evidence unavailable",e);}}
 private static RobertGroupEvidence readResources()throws IOException,XMLStreamException{
  var e=new RobertGroupEvidence();
  rows("omw-fra.tab",f->{if(f.length==3&&f[1].equals("fra:lemma"))e.member(f[0],f[2]);});
  rows("omw-fra-en-bridge.tsv",f->{if(f.length==2)for(String w:f[1].split("\\|"))if(w.length()>2&&!STOP.contains(w))add(e.bridge,norm(f[0]),norm(w));});
  rows("wordnet-3.0-glosses.tsv",f->{if(f.length==2){var ts=new HashSet<String>();for(String w:TOKEN.split(norm(f[1])))if(w.length()>2&&!STOP.contains(w))ts.add(w);e.gloss.put(f[0],Set.copyOf(ts));}});
  var factory=XMLInputFactory.newFactory();factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);factory.setProperty("javax.xml.stream.isSupportingExternalEntities",false);
  try(var in=resource("wolf.xml")){var r=factory.createXMLStreamReader(in);String id="";var literals=new ArrayList<String[]>();var targets=new ArrayList<String[]>();
   try{while(r.hasNext()){int event=r.next();if(event==XMLStreamConstants.START_ELEMENT){switch(r.getLocalName()){
    case "SYNSET"->{id="";literals.clear();targets.clear();}
    case "ID"->id=r.getElementText().replaceFirst("^eng-30-","");
    case "LITERAL"->{String note=Objects.toString(r.getAttributeValue(null,"lnote"),"");literals.add(new String[]{r.getElementText(),note});}
    case "DEF"->{String definition=r.getElementText();int marker=definition.indexOf("DEF(en)");if(marker>=0){String french=definition.substring(0,marker);for(String w:TOKEN.split(norm(french)))if(w.length()>2)add(e.frenchDefinitions,id,w);}}
    case "ILR"->{String type=Objects.toString(r.getAttributeValue(null,"type"),"");targets.add(new String[]{type,r.getElementText().replaceFirst("^eng-30-","")});}
    default->{}
   }}else if(event==XMLStreamConstants.END_ELEMENT&&r.getLocalName().equals("SYNSET")&&!id.isEmpty()){
    for(var literal:literals){String word=norm(literal[0]);e.member(id,word);for(String tag:literal[1].split(";")){if(tag.startsWith("ManVal")&&tag.contains("KO"))add(e.manuallyRejected,word,id);else if(tag.startsWith("ManAdd")||tag.startsWith("ManVal")&&tag.contains("OK"))add(e.manuallyReviewed,word,id);else if(tag.startsWith("gwa2012(")){try{double value=Double.parseDouble(tag.substring(8,tag.length()-1));if(value>=.9&&value<=1)add(e.highConfidenceLiterals,word,id);}catch(NumberFormatException ignored){/* malformed confidence cannot provide evidence */}}}}
    for(var t:targets){e.edge(id,t[0],t[1]);e.edge(t[1],"inverse_"+t[0],id);}
   }}}finally{r.close();}}
  return e;
 }
 public Set<String> senses(String word,String pos){var out=new TreeSet<String>();for(String id:ids.getOrDefault(norm(word),Set.of()))if(id.endsWith("-"+pos)||(pos.equals("a")&&id.endsWith("-s")))out.add(id);return Collections.unmodifiableSet(out);}
 public Set<String> sourceSenses(String word,String pos){var all=new TreeSet<>(senses(word,pos));all.removeAll(manuallyRejected.getOrDefault(norm(word),Set.of()));return Collections.unmodifiableSet(all);}
 public boolean reviewed(String word,String sense){return manuallyReviewed.getOrDefault(norm(word),Set.of()).contains(sense);}
 public Set<String> members(String id){return Collections.unmodifiableSet(words.getOrDefault(id,Set.of()));}
 public Set<String> neighbors(String id){var out=new TreeSet<String>();for(var en:relations.getOrDefault(id,Map.of()).entrySet())if(en.getKey().contains("hypernym")||en.getKey().contains("holo_")||en.getKey().contains("mero_")||Set.of("similar_to","inverse_similar_to","near_synonym","inverse_near_synonym","category_domain","inverse_category_domain","verb_group","inverse_verb_group").contains(en.getKey()))out.addAll(en.getValue());return out;}
 public double direct(String id,String context){String c=norm(context);if(words.getOrDefault(id,Set.of()).contains(c))return 1;if(frenchDefinitions.getOrDefault(id,Set.of()).contains(c))return .5;var translations=bridge.getOrDefault(c,Set.of());if(translations.size()>12)return 0;var g=gloss.getOrDefault(id,Set.of());return translations.stream().anyMatch(g::contains)?.5:0;}
 public double neighborhood(String id,String context){double best=0;for(String n:neighbors(id))best=Math.max(best,direct(n,context)/2);return best;}
 double derivationalSupport(String sense, String term) {
  var edges = relations.getOrDefault(sense, Map.of());
  return Set.of("derived", "inverse_derived", "eng_derivative", "inverse_eng_derivative").stream()
      .flatMap(type -> edges.getOrDefault(type, Set.of()).stream())
      .mapToDouble(related -> direct(related, term) / 2)
      .max().orElse(0);
 }
 public double support(String id,String context){return Math.max(direct(id,context),neighborhood(id,context));}
 public String forbiddenRelation(String sourceSense,String candidate,String pos){
  return forbiddenRelation(sourceSense,candidate,pos,false);
 }
 /** Part/whole and antonym contradictions remain vetoes even when lexical memberships overlap. */
 public String forbiddenRelation(String sourceSense,String candidate,String pos,boolean sameIdentifiedSense){
  return forbiddenRelation(sourceSense,candidate,pos,sameIdentifiedSense,false);
 }
 public String forbiddenRelation(String sourceSense,String candidate,String pos,boolean sameIdentifiedSense,boolean allowGeneralization){
  var candidateSenses=senses(candidate,pos);
  for(var relation:new TreeMap<>(relations.getOrDefault(sourceSense,Map.of())).entrySet()){
   String type=relation.getKey();
   if(type.contains("antonym")||type.contains("holo_")||type.contains("mero_")||type.contains("hypernym")&&!sameIdentifiedSense&&!allowGeneralization)
    if(relation.getValue().stream().anyMatch(candidateSenses::contains))return type;
  }return "";
 }
 public Set<String> glossTerms(String id){return Collections.unmodifiableSet(gloss.getOrDefault(id,Set.of()));}
 public double glossOverlap(String left,String right){var a=glossTerms(left);var b=glossTerms(right);if(a.isEmpty()||b.isEmpty())return 0;var union=new HashSet<>(a);union.addAll(b);var intersect=new HashSet<>(a);intersect.retainAll(b);return (double)intersect.size()/union.size();}
 public int synsets(){return words.size();}
 public Set<String> vocabulary(){return Collections.unmodifiableSet(ids.keySet());}
 public boolean explicitNearSynonym(String sense,String candidate,String pos){var senses=sourceSenses(candidate,pos);return relations.getOrDefault(sense,Map.of()).entrySet().stream().filter(e->e.getKey().equals("near_synonym")||e.getKey().equals("inverse_near_synonym")).anyMatch(e->e.getValue().stream().anyMatch(senses::contains));}
 boolean redundantAdjective(String original, String candidate) {
  return !Collections.disjoint(sourceSenses(original, "a"), sourceSenses(candidate, "a"));
 }
 /** Derivation utility: all reviewed readings, or all strong WOLF automatic readings when none are reviewed. */
 public boolean attestedAnimate(String word){var all=sourceSenses(word,"n");var readings=all.stream().filter(s->reviewed(word,s)).toList();if(readings.isEmpty())readings=all.stream().filter(s->highConfidenceLiterals.getOrDefault(norm(word),Set.of()).contains(s)).toList();return !readings.isEmpty()&&readings.stream().allMatch(this::animateSense);}
 public boolean possibleAnimate(String word){return sourceSenses(word,"n").stream().anyMatch(this::animateSense);}
 private record AncestorQuery(String synset, int depth) {}
 private final BoundedCache<AncestorQuery,Set<String>> ancestorCache = new BoundedCache<>(4096);

 public Set<String> ancestors(String start, int depth) {
  if (Boolean.getBoolean("ivorama.cache.disabled")) return traverseAncestors(start, depth);
  // Keep the public method's caller-owned set while caching an immutable value.
  return new HashSet<>(cachedAncestors(start, depth));
 }

 Set<String> cachedAncestors(String start, int depth) {
  if (Boolean.getBoolean("ivorama.cache.disabled")) return traverseAncestors(start, depth);
  return ancestorCache.get(new AncestorQuery(start, depth),
    query -> Set.copyOf(traverseAncestors(query.synset(), query.depth())));
 }

 private Set<String> traverseAncestors(String start,int depth){var seen=new HashSet<String>();var layer=Set.of(start);for(int i=0;i<=depth;i++){var next=new HashSet<String>();for(String id:layer)if(seen.add(id))next.addAll(relations.getOrDefault(id,Map.of()).getOrDefault("hypernym",Set.of()));layer=next;}return seen;}
 private boolean animateSense(String start){var queue=new ArrayDeque<String>();var seen=new HashSet<String>();queue.add(start);while(!queue.isEmpty()){String id=queue.remove();if(!seen.add(id))continue;
  // WordNet 3.0 person / animal concepts, not names or sentence-specific lexical exceptions.
  if(id.equals("00007846-n")||id.equals("00015388-n"))return true;
  for(var e:relations.getOrDefault(id,Map.of()).entrySet())if(e.getKey().equals("hypernym"))queue.addAll(e.getValue());
 }return false;}
}
