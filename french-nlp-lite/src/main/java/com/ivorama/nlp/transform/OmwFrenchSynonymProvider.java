package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import com.ivorama.nlp.core.SentenceAnalysis;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Open Multilingual Wordnet, French WOLF lexical data, grouped by synset and POS. */
public final class OmwFrenchSynonymProvider implements SynonymProvider {
 private static final String RESOURCE = "/lexicon/omw-fra.tab";
 private final Map<String,List<List<String>>> index;
 private final Map<String,List<String>> senseIds;
 private final Set<String> allLexicalPos = new HashSet<>();
 private final FrenchLeskDisambiguator lesk = new FrenchLeskDisambiguator();
 private final ContextualCandidateSelector contextualSelector = new ContextualCandidateSelector();
 public OmwFrenchSynonymProvider() {
  Map<String,List<String>> ids = new HashMap<>();
  index = load(ids, allLexicalPos);
  senseIds = ids;
 }
 @Override public Optional<String> synonym(TokenData token, SynonymRegister register) {
  String pos = pos(token.pos());
  if (pos == null) return Optional.empty();
  String lemma = normalize(token.word());
  List<List<String>> senses = index.get(lemma+"|"+pos);
  // Without contextual word-sense disambiguation, polysemy must not be guessed.
  if (senses == null || senses.size() != 1) return Optional.empty();
  for (String candidate : senses.get(0)) {
   if (candidate.equalsIgnoreCase(token.word()) || candidate.indexOf(' ')>=0 || candidate.indexOf('_')>=0) continue;
   if (!candidate.matches("[\\p{L}]+(?:[-'][\\p{L}]+)*")) continue;
   return Optional.of(caseLike(token.word(), candidate));
  }
  return Optional.empty();
 }
 @Override public Optional<String> synonym(TokenData token, SynonymRegister register, SentenceAnalysis context) {
  if (context == null) return Optional.empty();
  String part = pos(token.pos());
  if (part == null) return Optional.empty();
  List<List<String>> senses = index.get(normalize(token.word())+"|"+part);
  if (senses == null || senses.isEmpty()) return Optional.empty();
  List<String> ids = senseIds.get(normalize(token.word())+"|"+part);
  if (ids == null || ids.size() != senses.size()) return Optional.empty();
  // Do not turn a zero-evidence ranking into an asserted semantic equivalence.
  // The full forced ranking remains available through explain() for experiments.
  List<RankedCandidate> ranked = rank(token,context,part,senses,ids);
  ContextualCandidateSelector.Result decision = contextualSelector.evaluate(ranked);
  // A unique lexical sense does not require competing-sense disambiguation.
  // Multiple senses, however, require positive contextual evidence and a clear winner.
  if (senses.size() > 1 && decision.verdict() != ContextualCandidateSelector.Verdict.CONTEXT_SUPPORTED)
   return Optional.empty();
  return decision.best().map(c -> caseLike(token.word(),c.word()));
 }
 @Override public List<String> candidates(TokenData token, SynonymRegister register, SentenceAnalysis context) {
  if (context == null) return directCandidates(token);
  LinkedHashSet<String> result = new LinkedHashSet<>();
  for (RankedCandidate c : explain(token,context)) result.add(caseLike(token.word(),c.word()));
  return List.copyOf(result);
 }
 /** Diagnostic observable: classement de tous les couples (sens, synonyme).
  * Les scores sont des indices lexicaux, PAS des probabilités de validité. */
 public List<RankedCandidate> explain(TokenData token, SentenceAnalysis context) {
  if(context==null) return List.of();
  String part=pos(token.pos());
  if(part==null) return List.of();
  String key=normalize(token.word())+"|"+part;
  List<List<String>> senses=index.get(key);
  List<String> ids=senseIds.get(key);
  if(senses==null||ids==null||senses.size()!=ids.size()) return List.of();
  return rank(token,context,part,senses,ids);
 }
 /** Synsets partagés : preuve lexicale de proximité, non preuve de substituabilité. */
 public Set<String> sharedSynsets(String first,String second,String tag) {
  String part=pos(tag);
  if(part==null) return Set.of();
  Set<String> a=new HashSet<>(senseIds.getOrDefault(normalize(first)+"|"+part,List.of()));
  a.retainAll(senseIds.getOrDefault(normalize(second)+"|"+part,List.of()));
  return Collections.unmodifiableSet(a);
 }
 /** Similarité de Jaccard des sens attestés (0..1). Aucune valeur inventée. */
 public double senseSimilarity(String first,String second,String tag) {
  String part=pos(tag);
  if(part==null) return 0.0;
  Set<String> a=new HashSet<>(senseIds.getOrDefault(normalize(first)+"|"+part,List.of()));
  Set<String> b=new HashSet<>(senseIds.getOrDefault(normalize(second)+"|"+part,List.of()));
  if(a.isEmpty()||b.isEmpty()) return 0.0;
  int intersection=0;
  for(String id:a) if(b.contains(id)) intersection++;
  return (double)intersection/(a.size()+b.size()-intersection);
 }
 /** Vérification lexicale indépendante de la nature du synset source. */
 public boolean hasPartOfSpeech(String candidate, String tag) {
  String part=pos(tag);
  return part!=null && allLexicalPos.contains(normalize(candidate)+"|"+part);
 }
 /** Indique si une forme est indexée dans au moins une catégorie OMW. */
 public boolean isIndexed(String candidate) {
  String w=normalize(candidate);
  return allLexicalPos.contains(w+"|n") || allLexicalPos.contains(w+"|v")
      || allLexicalPos.contains(w+"|a") || allLexicalPos.contains(w+"|r");
 }
 /** Filtre de sécurité conservateur : la forme française doit être indexée
  * dans la catégorie attendue et ne doit pas également être indexée dans une
  * autre catégorie majeure. Ce test peut rejeter des homographes légitimes ;
  * il ne remplace pas une analyse morphosyntaxique indépendante. */
 public boolean hasUnambiguousPartOfSpeech(String candidate, String tag) {
  String expected=pos(tag);
  if(expected==null || !hasPartOfSpeech(candidate,tag)) return false;
  String word=normalize(candidate);
  for(String other:List.of("n","v","a","r")) {
   if(!other.equals(expected) && allLexicalPos.contains(word+"|"+other)) return false;
  }
  return true;
 }
 /** Retourne les candidats directs, y compris lorsque le mot est polysémique. */
 public List<String> directCandidates(TokenData token) {
  String part=pos(token.pos());
  if(part==null) return List.of();
  List<List<String>> senses=index.getOrDefault(normalize(token.word())+"|"+part,List.of());
  Set<String> result=new TreeSet<>();
  for(List<String> sense:senses) for(String candidate:sense) {
   if(!candidate.equalsIgnoreCase(token.word()) && !candidate.contains(" ")
      && !candidate.contains("_") && hasPartOfSpeech(candidate,token.pos())
      && candidate.matches("[\\p{L}]+(?:[-'][\\p{L}]+)*")) result.add(candidate);
  }
  return List.copyOf(result);
 }
 public record RankedCandidate(String word,String synset,double neighborhood,double leskBonus,
                               double candidateSpecificity,double total) {}
 private List<RankedCandidate> rank(TokenData token,SentenceAnalysis context,String part,
                                    List<List<String>> senses,List<String> ids) {
  Optional<String> leskWinner=lesk.selectSense(token,context,ids);
  Map<String,Integer> leskScores=lesk.scoreSenses(token,context,ids);
  List<RankedCandidate> ranked=new ArrayList<>();
  String source=normalize(token.word());
  for(int i=0;i<ids.size();i++) {
   String id=ids.get(i);
   double neighborhood=0.0;
   for(TokenData neighbor:context.tokens()) {
    if(neighbor.index()==token.index()||neighbor.isPunctuation()) continue;
    String np=pos(neighbor.pos());
    if(np==null) continue;
    List<String> neighborIds=senseIds.getOrDefault(normalize(neighbor.word())+"|"+np,List.of());
    if(neighborIds.contains(id)) neighborhood+=1.0/Math.sqrt(neighborIds.size());
   }
   // Continuous evidence for every sense; winner bonus retains the conservative Lesk decision.
   double leskBonus=Math.log1p(leskScores.getOrDefault(id,0))
       +(leskWinner.isPresent()&&leskWinner.get().equals(id)?1.0:0.0);
   for(String word:senses.get(i)) {
    if(word.equals(source)||word.contains(" ")||word.contains("_")) continue;
    if(!word.matches("[\\p{L}]+(?:[-'][\\p{L}]+)*")) continue;
    if(context.tokens().stream().anyMatch(t->normalize(t.word()).equals(word))) continue;
    if(!hasPartOfSpeech(word,token.pos())) continue;
    // A lexical synonym must be indexed with the SAME part of speech.
    // OMW POS is synset-level, so this check alone cannot prove inflection.
    int ambiguity=senseIds.getOrDefault(word+"|"+part,List.of()).size();
    double specificity=1.0/(1.0+ambiguity);
    double total=neighborhood+leskBonus+specificity;
    ranked.add(new RankedCandidate(word,id,neighborhood,leskBonus,specificity,total));
   }
  }
  ranked.sort(Comparator.comparingDouble(RankedCandidate::total).reversed()
      .thenComparing(RankedCandidate::word).thenComparing(RankedCandidate::synset));
  return List.copyOf(ranked);
 }

 private static String pos(String s) {
  String p=s.toUpperCase(Locale.ROOT);
  if (p.equals("NOUN")||p.equals("PROPN")||p.equals("NC")||p.equals("N")||p.equals("NPP")) return "n";
  if (p.startsWith("VERB")||p.equals("V")||p.equals("VINF")||p.equals("VPP")||p.equals("VPR")||p.equals("VS")) return "v";
  if (p.startsWith("ADJ")||p.equals("A")) return "a";
  if (p.startsWith("ADV")||p.equals("R")) return "r";
  return null;
 }
 private static String normalize(String s) { return Normalizer.normalize(s.trim(),Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
 private static String caseLike(String source,String candidate) {
  if (source.equals(source.toUpperCase(Locale.ROOT))) return candidate.toUpperCase(Locale.ROOT);
  if (!source.isEmpty() && Character.isUpperCase(source.charAt(0))) return Character.toUpperCase(candidate.charAt(0))+candidate.substring(1);
  return candidate;
 }
 private static Map<String,List<List<String>>> load(Map<String,List<String>> ids, Set<String> allLexicalPos) {
  Map<String,LinkedHashSet<String>> bySynset=new LinkedHashMap<>();
  try (InputStream in=OmwFrenchSynonymProvider.class.getResourceAsStream(RESOURCE)) {
   if (in==null) throw new IllegalStateException("OMW français absent: "+RESOURCE);
   try (BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
    String line;
    while ((line=reader.readLine())!=null) {
     if (line.isBlank()||line.startsWith("#")) continue;
     String[] fields=line.split("\t",3);
     if (fields.length!=3 || !fields[1].equals("fra:lemma")) continue;
     int dash=fields[0].lastIndexOf('-');
     if (dash<0) continue;
     String p=fields[0].substring(dash+1);
     if (p.equals("s")) p="a";
     if (!List.of("n","v","a","r").contains(p)) continue;
     allLexicalPos.add(normalize(fields[2])+"|"+p);
     bySynset.computeIfAbsent(fields[0].substring(0,dash)+"|"+p,k->new LinkedHashSet<>()).add(normalize(fields[2]));
    }
   }
  } catch(IOException e) { throw new IllegalStateException("Lecture OMW français impossible",e); }
  Map<String,List<List<String>>> idx=new HashMap<>();
  for (var e:bySynset.entrySet()) {
   String p=e.getKey().substring(e.getKey().lastIndexOf('|')+1);
   List<String> words=List.copyOf(e.getValue());
   if (words.size()<2) continue;
   for(String w:words) {
    idx.computeIfAbsent(w+"|"+p,k->new ArrayList<>()).add(words);
    ids.computeIfAbsent(w+"|"+p,k->new ArrayList<>()).add(e.getKey().replace("|", "-"));
   }
  }
  return idx;
 }
}
