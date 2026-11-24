package com.ivorama.nlp.transform;

import java.util.*;
/** Diagnostic lexical déterministe, sans modèle appris ni réseau.
 * Un score de voisinage n'est jamais une autorisation grammaticale ou contextuelle. */
public final class SemanticProximityScorer {
 public record Score(String source,String candidate,double proximity,boolean directSynset,
                     Set<String> sharedSynsets,String evidence) {
  public Score {
   if(!Double.isFinite(proximity)||proximity<0||proximity>1) throw new IllegalArgumentException("score hors [0,1]");
   sharedSynsets=Set.copyOf(sharedSynsets);
  }
 }
 private final OmwFrenchSynonymProvider omw;
 public SemanticProximityScorer(OmwFrenchSynonymProvider omw) { this.omw=Objects.requireNonNull(omw); }
 public Score score(String source,String candidate,String pos) {
  Set<String> shared=omw.sharedSynsets(source,candidate,pos);
  double value=omw.senseSimilarity(source,candidate,pos);
  return new Score(source,candidate,value,!shared.isEmpty(),shared,
    shared.isEmpty()?"NO_SHARED_OMW_SYNSET":"OMW_SHARED_SYNSET_JACCARD");
 }
 /** Classement stable ; les zéros restent visibles, jamais promus par défaut. */
 public List<Score> rank(String source,Collection<String> candidates,String pos) {
  List<Score> result=new ArrayList<>();
  for(String candidate:new TreeSet<>(candidates)) if(!source.equalsIgnoreCase(candidate))
   result.add(score(source,candidate,pos));
  result.sort(Comparator.comparingDouble(Score::proximity).reversed().thenComparing(Score::candidate));
  return List.copyOf(result);
 }
}
