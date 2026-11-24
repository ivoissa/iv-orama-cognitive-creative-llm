package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import com.ivorama.nlp.core.SentenceAnalysis;
import java.util.*;

/** Décision commune et explicable; ne dépend pas de l'ordre des fournisseurs. */
public final class CombinedSynonymProvider implements SynonymProvider {
 public record Source(String name, SynonymProvider provider) {}
 public record Candidate(String word, Set<String> sources, double semanticEvidence,
                         double sourceAgreement, double total) {}
 private final List<Source> sources;
 private final OmwFrenchSynonymProvider omw;
 private final DecisionWeights weights;
 private final SynonymCandidateValidator validator = new SynonymCandidateValidator();
 private RobertContextProvider robert;
 public CombinedSynonymProvider withRobert(RobertContextProvider provider){this.robert=provider;return this;}
 @Override public List<String> inventory(TokenData token,SynonymRegister register,SentenceAnalysis context){var all=new LinkedHashSet<String>();if(robert!=null)all.addAll(robert.inventory(token,register,context));for(var s:sources)all.addAll(s.provider().inventory(token,register,context));return List.copyOf(all);}
 @Override public CandidateDecision assess(TokenData token,SynonymRegister register,String candidate,SentenceAnalysis context){
  if (candidate == null || candidate.isBlank()) return CandidateDecision.reject("EMPTY_CANDIDATE");
  if (robert != null) {
   if (context == null) return CandidateDecision.reject("NO_CONTEXT_OR_PROTECTED_POS");
   var inventory = robert.inventory(token, register, context);
   if (inventory.stream().anyMatch(candidate::equalsIgnoreCase)) return robert.assess(token, register, candidate, context);
   if (!inventory.isEmpty()) return CandidateDecision.reject("ROBERT_PRIORITY_NO_APPROVED_RELATION");
   return robert.fallback(token, register, candidate, context);
  }
  if(isCuratedCandidate(token,register,candidate))return CandidateDecision.trusted();
  if(omw!=null&&context!=null){var selected=omw.synonym(token,register,context);if(selected.isPresent()&&selected.get().equalsIgnoreCase(candidate)){
   var rank=omw.explain(token,context).stream().filter(c->c.word().equalsIgnoreCase(candidate)).findFirst();
   if(rank.isPresent()&&(rank.get().neighborhood()>0||rank.get().leskBonus()>0)){var c=rank.get();return new CandidateDecision(true,"OMW_CONTEXT_SUPPORTED",c.total()/(1+c.total()),c.synset(),"Direct lexical candidate; legacy OMW evidence; no proximity-only approval");}
  }}return CandidateDecision.reject("CONTEXT_OR_SYNONYMY_UNPROVEN");
 }
 public CombinedSynonymProvider(List<Source> sources, OmwFrenchSynonymProvider omw) {
  this(sources,omw,DecisionWeights.defaults());
 }
 public CombinedSynonymProvider(List<Source> sources, OmwFrenchSynonymProvider omw, DecisionWeights weights) {
  if(sources.isEmpty()) throw new IllegalArgumentException("Aucune source lexicale");
  this.sources=List.copyOf(sources); this.omw=omw; this.weights=java.util.Objects.requireNonNull(weights);
 }
 @Override public List<String> candidates(TokenData token,SynonymRegister register,SentenceAnalysis context) {
  var result=new LinkedHashSet<String>();if(robert!=null)result.addAll(robert.candidates(token,register,context));
  explain(token,register,context).stream().map(Candidate::word).forEach(result::add);return List.copyOf(result);
 }
 @Override public Optional<String> synonym(TokenData token,SynonymRegister register) {
  return synonym(token,register,null);
 }
 @Override public Optional<String> synonym(TokenData token,SynonymRegister register,SentenceAnalysis context) {
  return candidates(token,register,context).stream().filter(c->assess(token,register,c,context).allowed()).findFirst();
 }
 @Override public boolean isCuratedCandidate(TokenData token,SynonymRegister register,String candidate) {
  return sources.stream().anyMatch(s->s.provider().isCuratedCandidate(token,register,candidate));
 }
 @Override public boolean supportsRegisters() { return robert!=null||sources.stream().anyMatch(s->s.provider().supportsRegisters()); }
 public List<Candidate> explain(TokenData token,SynonymRegister register,SentenceAnalysis context) {
  Map<String,Set<String>> origins=new TreeMap<>();
  for(Source source:sources) {
   for(String word:source.provider().candidates(token,register,context)) {
    if(!validator.isAcceptable(token,word,source.provider().isCuratedCandidate(token,register,word))) continue;
    // Une annotation de synset ne prouve pas toujours la catégorie de la forme.
    // Rejeter les candidats OMW dont la forme n'est pas indexée dans la même catégorie.
    // Le contrôle grammatical est transversal : un candidat indexé dans OMW
    // ne doit pas contourner le filtre parce qu'il vient de WOLF ou du TSV.
    // Une forme absente d'OMW reste indéterminée (et non prouvée correcte).
    if(omw != null && omw.isIndexed(word) && !omw.hasUnambiguousPartOfSpeech(word,token.pos())) continue;
    origins.computeIfAbsent(word.toLowerCase(Locale.ROOT),k->new TreeSet<>()).add(source.name());
   }
  }
  Map<String,Double> evidence=new HashMap<>();
  if(context!=null && omw!=null) {
   for(var ranked:omw.explain(token,context)) {
    evidence.merge(ranked.word().toLowerCase(Locale.ROOT),ranked.total(),Math::max);
   }
  }
  List<Candidate> result=new ArrayList<>();
  for(var entry:origins.entrySet()) {
   double semantic=evidence.getOrDefault(entry.getKey(),0.0);
   double agreement=(double)entry.getValue().size()/sources.size();
   double total=(weights.semantic()*(semantic/(1+semantic))+weights.agreement()*agreement)/(weights.semantic()+weights.agreement());
   result.add(new Candidate(entry.getKey(),Set.copyOf(entry.getValue()),semantic,agreement,total));
  }
  result.sort(Comparator.comparingDouble(Candidate::total).reversed().thenComparing(Candidate::word));
  return List.copyOf(result);
 }
}
