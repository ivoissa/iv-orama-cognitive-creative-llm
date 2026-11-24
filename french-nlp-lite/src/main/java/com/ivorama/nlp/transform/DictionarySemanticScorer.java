package com.ivorama.nlp.transform;

import java.util.*;

/** Sense-specific lexical overlap, not a probability of semantic equivalence. */
public final class DictionarySemanticScorer {
    private DictionarySemanticScorer() {}
    public static double overlap(Set<String> left, Set<String> right) {
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / union.size();
    }
}
