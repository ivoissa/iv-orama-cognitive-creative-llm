package fr.ivorama.cognitive;

import java.util.*;

/** Opt-in dual diagnosis for the integrated cycle. Reference remains authoritative
 * for FCI/RDO and provenance budgets. Disagreement/failure closes no obligation.
 * A SAT-only result cannot bypass a resource-limited reference or invent a proof.
 */
public final class CheckedReasoningBackend implements ReasoningBackend {
    private final ReasoningBackend candidate;
    public CheckedReasoningBackend(ReasoningBackend candidate) { this.candidate=Objects.requireNonNull(candidate); }
    public String id() { return "checked/"+candidate.id(); }
    public ModelExplorer.Analysis analyze(Collection<Formula> premises,Formula q,ModelExplorer.Budget budget) {
        var reference=ModelExplorer.analyze(premises,q,budget);
        final ModelExplorer.Analysis other;
        try { other=candidate.analyze(premises,q,budget); }
        catch(RuntimeException ex) { return limited("Candidate backend failed: "+ex.getClass().getSimpleName()); }
        if(reference.status()==ModelExplorer.Status.RESOURCE_LIMIT)return reference;
        if(other.status()!=reference.status())return limited("Backend disagreement/limit: reference="+reference.status()+", candidate="+other.status());
        return reference;
    }
    private static ModelExplorer.Analysis limited(String reason) {
        return new ModelExplorer.Analysis(ModelExplorer.Status.RESOURCE_LIMIT,List.of(),Optional.empty(),Optional.empty(),reason,false);
    }
}
