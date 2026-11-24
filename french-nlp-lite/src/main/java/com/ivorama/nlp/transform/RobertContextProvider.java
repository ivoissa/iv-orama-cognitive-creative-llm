package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import java.util.*;
/** Direct Robert candidates, source-first semantics; inflection belongs to the common engine. */
public final class RobertContextProvider implements SynonymProvider {
 private final RobertDisambiguator selector;private final MorphologicalEngine morphology;
 private final boolean weightedSyntax = Boolean.getBoolean("ivorama.robert.weighted_syntax");
 private final boolean diagnosticUnchecked=Boolean.getBoolean("ivorama.semantic.rejections.disabled");
 private final Map<SentenceAnalysis,Map<String,CandidateDecision>> decisions=Collections.synchronizedMap(new WeakHashMap<>());
 public RobertContextProvider(RobertDisambiguator selector,MorphologicalEngine morphology){this.selector=Objects.requireNonNull(selector);this.morphology=Objects.requireNonNull(morphology);}
 private String lemma(TokenData t){return morphology.analyze(t).lemma();}
 private String pos(TokenData t){return Set.of("PROPN","NPP").contains(t.pos())?"":t.isNoun()?"n":t.isAdjective()?"a":"";}
 private List<String> context(TokenData target,SentenceAnalysis a){return a==null?List.of():a.tokens().stream().filter(t->t.index()!=target.index()&&(t.isNoun()||t.isAdjective()||t.pos().startsWith("V")||t.pos().equals("AUX")||t.pos().startsWith("ADV"))).filter(t->!Set.of("PROPN","NPP").contains(t.pos())).map(morphology::analyze).map(MorphologicalFeatures::lemma).filter(s->!s.isBlank()).distinct().sorted().toList();}
 private List<String> dependencyContext(TokenData target, SentenceAnalysis analysis) {
  return analysis.tokens().stream()
      .filter(token -> token.index() == target.headIndex()
          || token.headIndex() == target.index()
          || target.headIndex() > 0 && token.headIndex() == target.headIndex())
      .filter(token -> token.index() != target.index())
      .filter(token -> !Set.of("PROPN", "NPP").contains(token.pos()))
      .filter(token -> token.isNoun() || token.isAdjective()
          || token.pos().startsWith("V") || token.pos().startsWith("ADV"))
      .map(this::lemma).filter(term -> !term.isBlank()).distinct().sorted().toList();
 }
 @Override
 public boolean compatibleAddition(TokenData token, String current, String candidate, SentenceAnalysis analysis) {
  String original = lemma(new TokenData(token.index(), current, token.pos()));
  if (original.isBlank()) return false;
  var evidence = selector.evidence();
  return !evidence.redundantAdjective(original, candidate)
      && evidence.sourceSenses(original, "a").stream()
          .noneMatch(sense -> !evidence.forbiddenRelation(sense, candidate, "a").isEmpty());
 }
 public List<String> inventory(TokenData t,SynonymRegister register,SentenceAnalysis a){String l=lemma(t),p=pos(t);return l.isBlank()||p.isBlank()?List.of():selector.index().lookup(l).stream().filter(m->m.relation().pos().equals(p)).map(RobertCandidateIndex.Match::candidate).map(RobertGroupEvidence::norm).filter(c->!c.equals(l)).distinct().sorted().toList();}
 @Override
 public List<String> dictionaryOrder(TokenData token, SynonymRegister register, SentenceAnalysis context) {
  String original = lemma(token), category = pos(token);
  return selector.index().lookup(original).stream()
      .filter(match -> !match.inverse() && match.relation().pos().equals(category))
      .map(RobertCandidateIndex.Match::candidate).map(RobertGroupEvidence::norm)
      .filter(candidate -> !candidate.equals(original)).distinct().toList();
 }
 private SynonymRegister markedRegister(RobertCandidateIndex.Match m){String term=m.inverse()?m.relation().head():m.relation().term();String raw=m.relation().raw();int at=raw.indexOf(term);String after=at<0?"":raw.substring(at+term.length()).stripLeading();if(after.matches("(?is)^(fam\\.|familier\\b|fm\\.).*"))return SynonymRegister.FAMILIER;if(after.matches("(?is)^(littér\\.|littéraire\\b|sout\\.).*"))return SynonymRegister.SOUTENU;return SynonymRegister.COURANT;}
 public synchronized CandidateDecision assess(TokenData t,SynonymRegister r,String c,SentenceAnalysis a){
  if(a==null)return CandidateDecision.reject("NO_CONTEXT_OR_PROTECTED_POS");
  String key=t.index()+":"+t.word()+":"+t.pos()+":"+r+":"+c;
  return decisions.computeIfAbsent(a,k->new HashMap<>()).computeIfAbsent(key,k->assessUncached(t,r,c,a,diagnosticUnchecked));
 }
 @Override
 public CandidateDecision assessDirect(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis analysis) {
  var strict = assess(token, register, candidate, analysis);
  if (strict.allowed()) return diagnosticUnchecked ? strict : checkNominalComplement(token, analysis, strict);
  if (!Set.of("NO_CONTEXT", "AMBIGUOUS_OR_UNSUPPORTED", "SOURCE_SENSE_NOT_REVIEWED",
      "AMBIGUOUS_CANDIDATES", "STRONGER_LEXICAL_RELATION_AVAILABLE").contains(strict.reason())) return strict;
  if (!(morphology instanceof LexiconMorphologicalEngine lexicon)) return strict;
  String original = lemma(token), category = pos(token), target = RobertGroupEvidence.norm(candidate);
  boolean forward = selector.index().lookup(original).stream().anyMatch(match -> !match.inverse()
          && match.relation().pos().equals(category) && RobertGroupEvidence.norm(match.candidate()).equals(target)
          && markedRegister(match) == register);
  if (!forward) return strict;
  var evidence = selector.evidence();
  var source = strict.sense().isEmpty() ? evidence.sourceSenses(original, category) : Set.of(strict.sense());
  var targetSenses = evidence.sourceSenses(target, category);
  if (source.isEmpty()) return strict;
  var readings = selector.scores(original, category, context(token, analysis)).stream()
      .filter(score -> source.contains(score.sense())).toList();
  double strongest = readings.stream().mapToDouble(RobertDisambiguator.SenseScore::total).max().orElse(0);
  // Without positive evidence choosing one reading, lexical coverage must still be unambiguous.
  var plausible = strict.sense().isEmpty() && strongest > 0
      ? readings.stream().filter(score -> score.total() == strongest)
          .map(RobertDisambiguator.SenseScore::sense).sorted().toList()
      : source.stream().sorted().toList();
  for (String sense : plausible) {
   boolean exact = targetSenses.contains(sense);
   if ((!exact && !evidence.explicitNearSynonym(sense, target, category))
       || !evidence.forbiddenRelation(sense, target, category, exact).isEmpty()
       || !FunctionalCompatibility.reason(analysis, token, original, target, sense, evidence, lexicon).isEmpty()) return strict;
  }
  return checkNominalComplement(token, analysis, new CandidateDecision(true, "DIRECT_ROBERT_LEXICAL_EVIDENCE", strict.score(), plausible.get(0),
      strict.reason() + "; forward Robert; synonymy attested for the leading source readings"));
 }
 private CandidateDecision checkNominalComplement(TokenData token, SentenceAnalysis analysis, CandidateDecision decision) {
  if (!token.isNoun() || analysis == null) return decision;
  var complements = analysis.dependentsOf(token.index()).stream()
      .filter(dependent -> dependent.isNoun() && (dependent.dependency().equals("nmod")
          || dependent.dependency().startsWith("nmod:"))).toList();
  if (complements.isEmpty()) return decision;
  String original = lemma(token);
  long groups = selector.index().lookup(original).stream()
      .filter(match -> !match.inverse() && match.relation().pos().equals("n"))
      .map(match -> match.relation().sense()).distinct().limit(2).count();
  if (groups < 2) return decision;
  var local = complements.stream().map(this::lemma).filter(term -> !term.isBlank()).toList();
  var scores = selector.scores(original, "n", local);
  double best = scores.stream().mapToDouble(RobertDisambiguator.SenseScore::direct).max().orElse(0);
  if (best > 0 && scores.stream().filter(score -> score.direct() == best).count() == 1
      && scores.stream().anyMatch(score -> score.sense().equals(decision.sense())
          && score.direct() == best)) return decision;
  return new CandidateDecision(false, "NOMINAL_COMPLEMENT_SENSE_UNSUPPORTED", decision.score(),
      decision.sense(), decision.evidence());
 }
 @Override
 public CandidateDecision assessUnchecked(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
  return assessUncached(token, register, candidate, context, true);
 }
 private CandidateDecision assessUncached(TokenData t,SynonymRegister r,String c,SentenceAnalysis a, boolean unchecked){
  if(a==null||pos(t).isBlank())return CandidateDecision.reject("NO_CONTEXT_OR_PROTECTED_POS");
  var f=morphology.analyze(t);boolean attested=morphology instanceof LexiconMorphologicalEngine l&&!l.readings(t.word(),t.pos()).isEmpty();if((!f.hasAgreementEvidence()&&!attested)||f.lemma().isBlank())return CandidateDecision.reject("SOURCE_MORPHOLOGY_UNRESOLVED");
  var matches=selector.index().lookup(f.lemma()).stream().filter(m->m.relation().pos().equals(pos(t))&&RobertGroupEvidence.norm(m.candidate()).equals(RobertGroupEvidence.norm(c))).toList();
  if(matches.isEmpty())return CandidateDecision.reject("NOT_A_DIRECT_ROBERT_RELATION");
  if(unchecked)return new CandidateDecision(true,"SEMANTIC_REJECTIONS_DISABLED",0,"unchecked","Diagnostic requested: sense, relation and register not validated; morphology and syntax remain mandatory");
  if(matches.stream().noneMatch(m->markedRegister(m)==r))return CandidateDecision.reject("REGISTER_NOT_ATTESTED");
  var terms = context(t, a);
  var decision = selector.assess(f.lemma(), pos(t), c, terms);
  if (t.isNoun() && Set.of("AMBIGUOUS_OR_UNSUPPORTED", "AMBIGUOUS_CANDIDATES").contains(decision.reason())) {
   var local = dependencyContext(t, a);
   if (!local.isEmpty()) {
    var contextual = weightedSyntax ? selector.assess(f.lemma(), pos(t), c, terms, local)
        : selector.assess(f.lemma(), pos(t), c, local);
    if (weightedSyntax && Set.of("AMBIGUOUS_OR_UNSUPPORTED", "AMBIGUOUS_CANDIDATES", "NO_CONTEXT",
        "NO_COMPATIBLE_CANDIDATE", "ROBERT_GROUP_SOURCE_CONFLICT", "SOURCE_SENSE_NOT_REVIEWED")
        .contains(contextual.reason())) {
     contextual = selector.assess(f.lemma(), pos(t), c, local);
    }
    if (contextual.allowed()) {
     decision = new CandidateDecision(true, contextual.reason(), contextual.score(),
         contextual.sense(), contextual.evidence() + "; context=syntactic_dependencies");
    }
   }
  }
  if (decision.allowed() && t.isNoun() && morphology instanceof LexiconMorphologicalEngine lexicon) {
   String conflict = FunctionalCompatibility.reason(a, t, f.lemma(), c, decision.sense(), selector.evidence(), lexicon);
   if (!conflict.isEmpty()) {
    return new CandidateDecision(false, conflict, decision.score(), decision.sense(), decision.evidence());
   }
  }
  return decision;
 }
 public List<String> candidates(TokenData t,SynonymRegister r,SentenceAnalysis a){return inventory(t,r,a).stream().filter(c->assess(t,r,c,a).allowed()).sorted(Comparator.<String>comparingDouble(c->assess(t,r,c,a).score()).reversed().thenComparing(Comparator.naturalOrder())).toList();}
 public Optional<String> synonym(TokenData t,SynonymRegister r){return Optional.empty();}
 public Optional<String> synonym(TokenData t,SynonymRegister r,SentenceAnalysis a){return candidates(t,r,a).stream().findFirst();}
 public boolean supportsRegisters(){return true;}
 public CandidateDecision fallback(TokenData t,SynonymRegister r,String c,SentenceAnalysis a){if(!selector.fallbackEnabled())return CandidateDecision.reject("ROBERT_UNCOVERED_FALLBACK_DISABLED");if(r!=SynonymRegister.COURANT)return CandidateDecision.reject("FALLBACK_REGISTER_NOT_ATTESTED");if(a==null||pos(t).isBlank())return CandidateDecision.reject("NO_CONTEXT_OR_PROTECTED_POS");return selector.assessOmw(lemma(t),pos(t),c,context(t,a));}
}
