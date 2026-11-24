package fr.ivorama.cognitive;

import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/**
 * P3.2b: advisory-only reasoning notes for a model draft. No generation and no
 * mutation of an answer, knowledge base, or FCI question. Source excerpts are
 * taken only from the caller-declared reference, never from the LLM output.
 * Minimality below means deletion-minimal within the bounded reference set.
 */
public final class P32SuggestedCorrections {
    private P32SuggestedCorrections() { }
    public enum Action { CHALLENGE, NEEDS_SOURCE, INTERNAL_CONFLICT, UNRESOLVED, NONE }
    public record Suggestion(String claimId, String claimExcerpt, Action action,
                             List<String> referenceExcerpts, String explanation) {
        public Suggestion {
            Objects.requireNonNull(claimId); Objects.requireNonNull(claimExcerpt);
            Objects.requireNonNull(action); referenceExcerpts = List.copyOf(referenceExcerpts);
            Objects.requireNonNull(explanation);
        }
    }
    public record Advice(P31CandidateAnswerAudit.Review review, List<Suggestion> suggestions,
                         boolean complete, String limitation) {
        public Advice {
            Objects.requireNonNull(review); suggestions = List.copyOf(suggestions);
            Objects.requireNonNull(limitation);
        }
        /** The original LLM draft must never be auto-replaced by this advisory artifact. */
        public boolean permitsAutomaticReplacement() { return false; }
    }

    public static Advice propose(P31CandidateAnswerAudit.Review review, ModelExplorer.Budget budget) {
        Objects.requireNonNull(review); Objects.requireNonNull(budget);
        if (review.status() == P31CandidateAnswerAudit.Status.RESOURCE_LIMIT
                || review.status() == P31CandidateAnswerAudit.Status.INCONSISTENT_REFERENCE
                || (review.status() == P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE
                    && review.claims().isEmpty())) {
            return new Advice(review, List.of(new Suggestion("", "", Action.UNRESOLVED, List.of(),
                    "Référence ou traduction non validée : aucune correction formelle proposée.")), false,
                    "Aucune correction sans contexte cohérent et traduction fiable.");
        }
        var notes = new ArrayList<Suggestion>();
        boolean partial = review.status() == P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE;
        if (partial) notes.add(new Suggestion("", "", Action.UNRESOLVED, List.of(),
                "Au moins un passage reste non traduit : les diagnostics suivants sont partiels."));
        boolean bounded = !partial;
        Map<String,List<String>> justifications = Map.of();
        boolean needsReasons = review.claims().stream().anyMatch(c -> c.status() == P31CandidateAnswerAudit.Status.CONTRADICTED);
        if (needsReasons) {
            try { justifications = contradictorySupports(review, budget); }
            catch (RuntimeException ex) { bounded = false; }
        }
        boolean internalConflict = review.status() == P31CandidateAnswerAudit.Status.CONTRADICTED
                && review.claims().stream().noneMatch(c -> c.status() == P31CandidateAnswerAudit.Status.CONTRADICTED);
        if (internalConflict) {
            notes.add(new Suggestion("", "", Action.INTERNAL_CONFLICT, List.of(),
                    "Contradiction entre assertions du brouillon ; aucune correction de contenu n'est inférée."));
        }
        for (var claim : review.claims()) {
            var status = claim.status();
            if (status == P31CandidateAnswerAudit.Status.CONTRADICTED) {
                List<String> evidence = justifications.get(claim.premiseId());
                if (evidence == null || evidence.isEmpty()) {
                    bounded = false;
                    notes.add(new Suggestion(claim.premiseId(),claim.excerpt(),Action.UNRESOLVED,List.of(),
                            "Contradiction détectée, mais support sourcé non reconstitué dans les limites."));
                } else {
                    notes.add(new Suggestion(claim.premiseId(),claim.excerpt(),Action.CHALLENGE,evidence,
                            "Cette assertion contredit les extraits de référence déclarés ; vérifier ou retirer cette assertion."
                            + " La référence n'est pas une preuve de vérité empirique."));
                }
            } else if (status == P31CandidateAnswerAudit.Status.CONSISTENT_UNSUPPORTED) {
                notes.add(new Suggestion(claim.premiseId(),claim.excerpt(),Action.NEEDS_SOURCE,List.of(),
                        "Compatible avec la référence, mais non déduit : demander un appui externe."));
            }
        }
        return new Advice(review,notes,bounded, bounded
                ? "Suggestions conditionnelles, sans modification automatique de la génération."
                : "Analyse partielle : une justification n'a pas pu être reconstruite sous budget.");
    }

    private static Map<String,List<String>> contradictorySupports(P31CandidateAnswerAudit.Review review,
                                                                   ModelExplorer.Budget budget) {
        String joined = review.reference() + "\n" + review.draft();
        var parsed = new FrenchStructuredTranslator().analyzeText(joined);
        if (!parsed.unresolved().isEmpty()) {
            if (review.status() != P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE
                    || !DiscourseSegments.scan(joined).unresolved().isEmpty())
                throw new IllegalArgumentException("Untranslated input");
            for (var segment : parsed.segments())
                if (!segment.interpreted() && segment.start() < review.reference().length()+1)
                    throw new IllegalArgumentException("Reference not completely interpreted");
        }
        List<Premise> reference = new ArrayList<>();
        List<Premise> draft = new ArrayList<>();
        int boundary = review.reference().length();
        for (Premise p : parsed.propositions()) {
            String[] offsets = p.evidence().source().split(":");
            int start = Integer.parseInt(offsets[1]);
            int end = Integer.parseInt(offsets[2]);
            if (end <= boundary) reference.add(p);
            else if (start >= boundary + 1) draft.add(p);
            else throw new IllegalArgumentException("Boundary overlap");
        }
        // Do not promise source-minimal explanations for arbitrarily large contexts.
        if (reference.size() > 12 || draft.size() > 12 || review.claims().size() > 12)
            throw new IllegalArgumentException("Explanation budget");
        List<Premise> all = new ArrayList<>(reference); all.addAll(draft);
        var compiled = StructuredLogicCompiler.compile(new Problem(joined, parsed.entities(),
                parsed.predicates(), all, draft.getFirst().expression(), List.of(), List.of()));
        var result = new LinkedHashMap<String,List<String>>();
        for (var claim : review.claims()) {
            if (claim.status() != P31CandidateAnswerAudit.Status.CONTRADICTED) continue;
            Formula goal = compiled.premiseIds().get(claim.premiseId());
            Premise original = draft.stream().filter(p->p.id().equals(claim.premiseId()))
                    .findFirst().orElseThrow(()->new IllegalArgumentException("Unknown candidate source id"));
            if (goal == null || !original.evidence().excerpt().equals(claim.excerpt()))
                throw new IllegalArgumentException("Claim id or excerpt differs from parsed draft");
            List<Premise> support = new ArrayList<>(reference);
            List<Formula> initial = support.stream().map(p->compiled.premiseIds().get(p.id())).toList();
            // A forged or stale review must not produce a sourced challenge.
            if (ModelExplorer.analyze(initial, goal, budget).status() != ModelExplorer.Status.PROVEN_FALSE)
                throw new IllegalArgumentException("Claim is not independently contradicted by the reference");
            for (int i=0; i<support.size();) {
                List<Formula> others = new ArrayList<>();
                for (int j=0;j<support.size();j++)if (j!=i) others.add(compiled.premiseIds().get(support.get(j).id()));
                var finding = ModelExplorer.analyze(others,goal,budget);
                if (finding.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                    throw new IllegalArgumentException("Insufficient budget to justify minimal support");
                if (finding.status() == ModelExplorer.Status.PROVEN_FALSE) support.remove(i);
                else i++;
            }
            List<String> excerpts = support.stream().map(p->p.evidence().excerpt()).toList();
            if (excerpts.isEmpty()) throw new IllegalArgumentException("Empty support in consistent reference");
            result.put(claim.premiseId(), excerpts);
        }
        return result;
    }
}
