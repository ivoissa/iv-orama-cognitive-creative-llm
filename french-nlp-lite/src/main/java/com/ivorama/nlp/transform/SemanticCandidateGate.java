package com.ivorama.nlp.transform;

import java.util.Objects;

/** A score is evidence for investigation, not authorization to substitute.
 * No lexical exceptions or corpus-specific words are embedded in this policy. */
public final class SemanticCandidateGate {
    public enum Decision { REVIEW_CONTEXT, REVIEW_MORPHOLOGY, REVIEW_MULTIWORD, INSUFFICIENT_EVIDENCE }
    public record Assessment(Decision decision, double proximity, String explanation) {}
    public Assessment assess(double proximity, boolean sharedSynset, String rejectionReason) {
        if (!Double.isFinite(proximity) || proximity < 0 || proximity > 1)
            throw new IllegalArgumentException("proximity must be in [0,1]");
        Objects.requireNonNull(rejectionReason);
        if (rejectionReason.startsWith("MULTIWORD_CANDIDATE"))
            return new Assessment(Decision.REVIEW_MULTIWORD, proximity, "Phrase-level realization required");
        if (rejectionReason.startsWith("TARGET_PART_OF_SPEECH_OR_FORM"))
            return new Assessment(Decision.REVIEW_MORPHOLOGY, proximity, "POS/form attestation required");
        if (sharedSynset && proximity > 0)
            return new Assessment(Decision.REVIEW_CONTEXT, proximity, "Shared synset; disambiguate in sentence");
        return new Assessment(Decision.INSUFFICIENT_EVIDENCE, proximity, "No positive lexical evidence");
    }
}
