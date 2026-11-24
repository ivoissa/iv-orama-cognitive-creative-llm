package com.ivorama.nlp.transform;
import java.util.Optional;
/** Optional.empty() signifie accepté; sinon contient la raison du rejet. */
@FunctionalInterface public interface CandidateFilter {
    Optional<String> rejectReason(CandidateValidation candidate);
}
