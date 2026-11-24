package com.ivorama.nlp.transform;
import java.util.List;
import java.util.Optional;
/** Chaîne de filtres injectables : aucun lexique spécifique au corpus. */
public final class ValidationPipeline {
    private final List<CandidateFilter> filters;
    public ValidationPipeline(List<CandidateFilter> filters) { this.filters = List.copyOf(filters); }
    public Optional<String> rejectReason(CandidateValidation candidate) {
        for (CandidateFilter filter : filters) {
            Optional<String> rejected = filter.rejectReason(candidate);
            if (rejected.isPresent()) return rejected;
        }
        return Optional.empty();
    }
    public boolean accepts(CandidateValidation candidate) { return rejectReason(candidate).isEmpty(); }
}
