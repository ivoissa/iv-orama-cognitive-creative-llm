package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Conservative cross-lingual simplified Lesk: French context lemmas are bridged
 * to English WordNet lemmas through OMW-aligned synsets; English gloss tokens
 * are compared with that bridge. No semantic equivalence guarantee. */
public final class FrenchLeskDisambiguator implements SemanticSimilarityScorer {
 private static final Set<String> STOP = Set.of("the","and","for","with","that","this","from","into","are","was","were","has","have","had","not","one","two","its","his","her","you","your","which","who","how","when","where","can","may","all","any","other","such","than","but","also","being","been","use","used","using","make","made","something","someone","some","their","they","them","there","then","more","most","very","what","will","would","could","should","does","did","about","over","under","out","off","each","per","act","thing");
 private final Map<String,Set<String>> bridge;
 private final Map<String,Set<String>> glosses;
 public FrenchLeskDisambiguator() {
  bridge = readBridge("/lexicon/omw-fra-en-bridge.tsv");
  glosses = readGlosses("/lexicon/wordnet-3.0-glosses.tsv");
 }
 /** Scores Lesk bruts, calculés pour chaque sens; aucun seuil ni choix masqué. */
 public Map<String,Integer> scoreSenses(TokenData target, SentenceAnalysis sentence, List<String> synsets) {
  if (sentence == null || synsets == null) return Map.of();
  Set<String> context = new HashSet<>();
  for (TokenData t : sentence.tokens()) {
   if (t.index() == target.index() || t.isPunctuation()) continue;
   Set<String> translations = bridge.getOrDefault(normalize(t.word()),Set.of());
   if (!translations.isEmpty() && translations.size() <= 12) context.addAll(translations);
  }
  Map<String,Integer> scores = new LinkedHashMap<>();
  for (String synset : synsets) {
   Set<String> gloss = glosses.getOrDefault(synset,Set.of());
   int score = 0;
   for (String word : context) if (gloss.contains(word)) score++;
   scores.put(synset,score);
  }
  return Collections.unmodifiableMap(scores);
 }
 public Optional<String> selectSense(TokenData target, SentenceAnalysis sentence, List<String> synsets) {
  if (synsets == null || synsets.size() < 2) return Optional.empty();
  Map<String,Integer> scores = scoreSenses(target,sentence,synsets);
  int best=0, second=0; String winner=null;
  for (var e : scores.entrySet()) {
   int score=e.getValue();
   if(score>best) {second=best;best=score;winner=e.getKey();}
   else if(score>second) second=score;
  }
  return best>=2 && best-second>=2 ? Optional.of(winner) : Optional.empty();
 }
 private static String normalize(String value) {return Normalizer.normalize(value.toLowerCase(Locale.ROOT),Normalizer.Form.NFC);}
 private static Map<String,Set<String>> readBridge(String resource) {
  Map<String,Set<String>> map=new HashMap<>();
  try (BufferedReader r=reader(resource)) {
   String line;
   while ((line=r.readLine())!=null) {
    String[] fields=line.split("\t",2);
    if (fields.length==2) map.put(fields[0],Set.of(fields[1].split("\\|")));
   }
  } catch(IOException e){throw new IllegalStateException("OMW English bridge read failed",e);}
  return map;
 }
 private static Map<String,Set<String>> readGlosses(String resource) {
  Map<String,Set<String>> map=new HashMap<>();
  try (BufferedReader r=reader(resource)) {
   String line;
   while ((line=r.readLine())!=null) {
    String[] fields=line.split("\t",2);
    if(fields.length!=2) continue;
    Set<String> terms=new HashSet<>();
    for(String w:fields[1].toLowerCase(Locale.ROOT).split("[^a-z]+"))
     if(w.length()>=3 && !STOP.contains(w)) terms.add(w);
    map.put(fields[0],terms);
   }
  }catch(IOException e){throw new IllegalStateException("WordNet gloss read failed",e);}
  return map;
 }
 private static BufferedReader reader(String resource) {
  InputStream in=FrenchLeskDisambiguator.class.getResourceAsStream(resource);
  if(in==null) throw new IllegalStateException("Missing resource: "+resource);
  return new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));
 }
}
