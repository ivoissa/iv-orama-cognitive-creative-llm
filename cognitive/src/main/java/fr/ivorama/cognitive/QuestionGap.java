package fr.ivorama.cognitive;

import java.util.*;

/**
 * Legacy FCI-001 logical evaluator: given an explicitly defined question,
 * assesses which facets are determined by the admitted context. It does
 * NOT represent the interrogative act that introduces a gap (FCI-002). Only explicitly ADMITTED response claims
 * modify that context; even those are assumptions, NOT independently verified
 * facts about the physical world.
 *
 * No machine learning, external calls or new inference rules.
 */
public final class QuestionGap {
    private QuestionGap() {}

    public record Facet(String label, Formula target) {
        public Facet {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Blank facet label");
            Objects.requireNonNull(target, "target");
        }
    }

    public record Question(List<Facet> facets) {
        public Question {
            facets = List.copyOf(Objects.requireNonNull(facets, "facets"));
            if (facets.isEmpty()) throw new IllegalArgumentException("Question requires at least one facet");
            Set<String> labels = new HashSet<>();
            for (Facet facet : facets) {
                if (!labels.add(facet.label())) throw new IllegalArgumentException("Duplicate facet label");
            }
        }

        public static Question of(Formula target) {
            return new Question(List.of(new Facet("question", target)));
        }
    }

    public enum Admission { PROPOSED, ADMITTED }

    public record Claim(Formula proposition, String provenance, Admission admission) {
        public Claim {
            Objects.requireNonNull(proposition, "proposition");
            if (provenance == null || provenance.isBlank())
                throw new IllegalArgumentException("Missing provenance");
            Objects.requireNonNull(admission, "admission");
        }
    }

    public record Response(List<Claim> claims) {
        public Response { claims = List.copyOf(Objects.requireNonNull(claims, "claims")); }
        public static Response empty() { return new Response(List.of()); }
    }

    public enum Status { OPEN, PARTIAL, CLOSED_CONDITIONALLY, INCONSISTENT, RESOURCE_LIMIT }

    public record FacetResult(String label, Formula target,
                              ModelExplorer.Status before, ModelExplorer.Status after) {
        public boolean resolvedBefore() { return determined(before); }
        public boolean resolvedAfter() { return determined(after); }
    }

    public record Report(Status status, int resolvedBefore, int resolvedAfter,
                         int admittedClaims, int ignoredProposals,
                         List<FacetResult> facets, String explanation) {
        public Report { facets = List.copyOf(facets); }
        /** Ratio of logically resolved facets. Not a truth, accuracy, or evidence quality score. */
        public double closure() {
            if (status == Status.INCONSISTENT || status == Status.RESOURCE_LIMIT) return Double.NaN;
            return (double) resolvedAfter / facets.size();
        }
        public int newlyResolved() {
            if (status == Status.INCONSISTENT || status == Status.RESOURCE_LIMIT) return 0;
            return resolvedAfter - resolvedBefore;
        }
    }

    private static boolean determined(ModelExplorer.Status s) {
        return s == ModelExplorer.Status.PROVEN_TRUE || s == ModelExplorer.Status.PROVEN_FALSE;
    }

    /**
     * Evaluate a candidate answer against current context and a finite semantic
     * budget. Proposed claims are excluded; admitted claims have explicit source
     * labels but no claim of independent empirical verification is made.
     */
    public static Report assess(Collection<Formula> context, Question question,
                                Response response, ModelExplorer.Budget budget) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(budget, "budget");
        List<Formula> initial = List.copyOf(context);
        List<Formula> extended = new ArrayList<>(initial);
        int admitted = 0, proposed = 0;
        for (Claim claim : response.claims()) {
            if (claim.admission() == Admission.ADMITTED) {
                extended.add(claim.proposition());
                admitted++;
            } else proposed++;
        }
        List<FacetResult> facets = new ArrayList<>();
        int before = 0, after = 0;
        boolean inconsistent = false, limited = false;
        for (Facet facet : question.facets()) {
            var oldResult = ModelExplorer.analyze(initial, facet.target(), budget);
            var newResult = ModelExplorer.analyze(extended, facet.target(), budget);
            if (oldResult.status() == ModelExplorer.Status.INCONSISTENT
                    || newResult.status() == ModelExplorer.Status.INCONSISTENT) inconsistent = true;
            if (oldResult.status() == ModelExplorer.Status.RESOURCE_LIMIT
                    || newResult.status() == ModelExplorer.Status.RESOURCE_LIMIT) limited = true;
            if (determined(oldResult.status())) before++;
            if (determined(newResult.status())) after++;
            facets.add(new FacetResult(facet.label(), facet.target(), oldResult.status(), newResult.status()));
        }
        // A contradiction prevents any valid closure, even if other facets have a value.
        if (inconsistent) return new Report(Status.INCONSISTENT, before, 0, admitted, proposed, facets,
                "Incompatible admitted premises: question not repaired; inspect conflicting sources");
        if (limited) return new Report(Status.RESOURCE_LIMIT, before, 0, admitted, proposed, facets,
                "Finite analysis budget exceeded: cannot certify closure");
        Status status = after == 0 ? Status.OPEN : after == facets.size()
                ? Status.CLOSED_CONDITIONALLY : Status.PARTIAL;
        return new Report(status, before, after, admitted, proposed, facets,
                "Closure relative to admitted premises only; provenance is recorded, not independently verified");
    }
}
