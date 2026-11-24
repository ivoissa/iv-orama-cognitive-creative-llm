package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import com.ivorama.nlp.core.SentenceAnalysis;
import java.util.Optional;
public final class FallbackSynonymProvider implements SynonymProvider {
 private final SynonymProvider first, second;
 public FallbackSynonymProvider(SynonymProvider first, SynonymProvider second) {
  this.first = first; this.second = second;
 }
 @Override public Optional<String> synonym(TokenData token, SynonymRegister register) {
  Optional<String> result = first.synonym(token, register);
  return result.isPresent() ? result : second.synonym(token, register);
 }
 @Override public Optional<String> synonym(TokenData token, SynonymRegister register, SentenceAnalysis context) {
  Optional<String> result = first.synonym(token, register, context);
  return result.isPresent() ? result : second.synonym(token, register, context);
 }
 @Override public boolean isCuratedCandidate(TokenData token, SynonymRegister register, String candidate) {
  // La provenance du candidat ne depend pas de la presence d'un autre candidat
  // dans le premier fournisseur (qui peut avoir ete refuse en contexte).
  return first.isCuratedCandidate(token, register, candidate)
      || second.isCuratedCandidate(token, register, candidate);
 }
 @Override public java.util.List<String> inventory(TokenData t,SynonymRegister r,SentenceAnalysis a){var all=new java.util.LinkedHashSet<String>(first.inventory(t,r,a));all.addAll(second.inventory(t,r,a));return java.util.List.copyOf(all);}
 @Override public java.util.List<String> candidates(TokenData t,SynonymRegister r,SentenceAnalysis a){return inventory(t,r,a);}
 @Override public CandidateDecision assess(TokenData t,SynonymRegister r,String c,SentenceAnalysis a){var d=first.assess(t,r,c,a);return d.allowed()?d:second.assess(t,r,c,a);}
}
