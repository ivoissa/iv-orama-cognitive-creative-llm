package com.ivorama.nlp.transform;

import java.util.*;

/** Experimental, conservative contextual ranking of OMW sense candidates.
 * Does not override morphological validation or authorize a substitution.
 * Scores are evidence, not calibrated probabilities. */
public final class ContextualCandidateSelector {
    public enum Verdict { CONTEXT_SUPPORTED, AMBIGUOUS, NO_CONTEXT_EVIDENCE }
    public record Result(Verdict verdict, Optional<OmwFrenchSynonymProvider.RankedCandidate> best,
                         double margin, List<OmwFrenchSynonymProvider.RankedCandidate> ranked) {}

    public Result evaluate(List<OmwFrenchSynonymProvider.RankedCandidate> candidates) {
        Objects.requireNonNull(candidates);
        List<OmwFrenchSynonymProvider.RankedCandidate> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(OmwFrenchSynonymProvider.RankedCandidate::total).reversed()
                        .thenComparing(OmwFrenchSynonymProvider.RankedCandidate::word)
                        .thenComparing(OmwFrenchSynonymProvider.RankedCandidate::synset)).toList();
        if (sorted.isEmpty()) return new Result(Verdict.NO_CONTEXT_EVIDENCE, Optional.empty(), 0, sorted);
        var top = sorted.get(0);
        // Distinct synsets matter; synonyms belonging to the same sense must not
        // compete with each other as evidence of sense ambiguity.
        double secondSense = sorted.stream().filter(c -> !c.synset().equals(top.synset()))
                .mapToDouble(OmwFrenchSynonymProvider.RankedCandidate::total)
                .max().orElse(Double.NEGATIVE_INFINITY);
        double margin = Double.isFinite(secondSense) ? top.total() - secondSense : top.total();
        boolean hasContext = top.neighborhood() > 0 || top.leskBonus() > 0;
        Verdict verdict = !hasContext ? Verdict.NO_CONTEXT_EVIDENCE
                : margin <= 0 ? Verdict.AMBIGUOUS : Verdict.CONTEXT_SUPPORTED;
        return new Result(verdict, Optional.of(top), margin, sorted);
    }
}
