package fr.ivorama.cognitive;

import java.util.*;

/**
 * FCI-002: a question ACT introduces an obligation (gap) into an otherwise
 * interrogatively stable representational universe. Logical indetermination is
 * a separate property, and can exist before any question is posed.
 * This model neither assumes nor proves subjective consciousness.
 */
public final class InterrogativeUniverse {
    public enum Origin { HUMAN, SYSTEM }
    public enum Phase { STABLE, OPEN, PARTIAL, RESTORED_CONDITIONALLY, INCONSISTENT, RESOURCE_LIMIT }

    public record QuestionAct(String id, Origin origin, QuestionGap.Question question) {
        public QuestionAct {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing question id");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(question, "question");
        }
    }

    /** Explicit asserted answers to individual yes/no facets. Supporting claims
     * are considered only if the caller marks them ADMITTED, which does NOT
     * establish empirical truth. */
    public record AnswerAct(Map<String, Boolean> positions,
                            QuestionGap.Response supportingClaims, String source) {
        public AnswerAct {
            positions = Map.copyOf(Objects.requireNonNull(positions, "positions"));
            Objects.requireNonNull(supportingClaims, "supportingClaims");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("Missing answer source");
        }
    }

    public record Snapshot(Phase phase, int introducedFacets, int repairedFacets,
                           List<String> outstanding, double repairRatio,
                           String explanation) {
        public Snapshot { outstanding = List.copyOf(outstanding); }
        public boolean stable() { return phase == Phase.STABLE || phase == Phase.RESTORED_CONDITIONALLY; }
    }

    private final List<Formula> context;
    private final QuestionAct questionAct;
    private final AnswerAct answerAct;

    private InterrogativeUniverse(List<Formula> context, QuestionAct questionAct, AnswerAct answerAct) {
        this.context = context;
        this.questionAct = questionAct;
        this.answerAct = answerAct;
    }

    public static InterrogativeUniverse stable(Collection<Formula> context) {
        return new InterrogativeUniverse(List.copyOf(Objects.requireNonNull(context, "context")), null, null);
    }

    public InterrogativeUniverse ask(QuestionAct act) {
        Objects.requireNonNull(act, "question act");
        if (questionAct != null) throw new IllegalStateException("A question has already been posed in this session");
        return new InterrogativeUniverse(context, act, null);
    }

    public InterrogativeUniverse respond(AnswerAct act) {
        Objects.requireNonNull(act, "answer act");
        if (questionAct == null) throw new IllegalStateException("Cannot repair without a posed question");
        Set<String> labels = new HashSet<>();
        for (QuestionGap.Facet facet : questionAct.question().facets()) labels.add(facet.label());
        for (String name : act.positions().keySet()) {
            if (!labels.contains(name)) throw new IllegalArgumentException("Response refers to unknown facet: " + name);
        }
        return new InterrogativeUniverse(context, questionAct, act);
    }

    public Snapshot snapshot(ModelExplorer.Budget budget) {
        Objects.requireNonNull(budget, "budget");
        if (questionAct == null) {
            return new Snapshot(Phase.STABLE, 0, 0, List.of(), 1.0,
                    "Stable in the interrogative sense: no active question; no claim of total knowledge");
        }
        List<QuestionGap.Facet> facets = questionAct.question().facets();
        List<String> pending = new ArrayList<>();
        if (answerAct == null) {
            for (var facet : facets) pending.add(facet.label());
            return new Snapshot(Phase.OPEN, facets.size(), 0, pending, 0.0,
                    "The question act creates a response obligation, even when answers are already derivable");
        }
        QuestionGap.Report assessed = QuestionGap.assess(context, questionAct.question(),
                answerAct.supportingClaims(), budget);
        if (assessed.status() == QuestionGap.Status.INCONSISTENT
                || assessed.status() == QuestionGap.Status.RESOURCE_LIMIT) {
            for (var facet : facets) pending.add(facet.label());
            return new Snapshot(assessed.status() == QuestionGap.Status.INCONSISTENT
                    ? Phase.INCONSISTENT : Phase.RESOURCE_LIMIT,
                    facets.size(), 0, pending, Double.NaN,
                    "No certified repair: inconsistent premises or insufficient calculation resources");
        }
        int repaired = 0;
        int disagreements = 0;
        for (var facet : assessed.facets()) {
            Boolean given = answerAct.positions().get(facet.label());
            boolean matches = given != null && (given && facet.after() == ModelExplorer.Status.PROVEN_TRUE
                    || !given && facet.after() == ModelExplorer.Status.PROVEN_FALSE);
            if (matches) repaired++;
            else {
                pending.add(facet.label());
                if (given != null) disagreements++;
            }
        }
        Phase phase = repaired == facets.size() ? Phase.RESTORED_CONDITIONALLY
                : repaired == 0 ? Phase.OPEN : Phase.PARTIAL;
        return new Snapshot(phase, facets.size(), repaired, pending,
                (double) repaired / facets.size(),
                "Question introduces the gap; explicit answered facets are checked against premises"
                        + "; unresolved or incompatible answers=" + disagreements
                        + "; conditional closure is not empirical truth");
    }

    public Optional<QuestionAct> activeQuestion() { return Optional.ofNullable(questionAct); }
    public boolean hasAnswerAct() { return answerAct != null; }
}
