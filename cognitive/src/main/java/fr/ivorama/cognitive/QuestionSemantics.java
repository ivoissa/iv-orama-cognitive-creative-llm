package fr.ivorama.cognitive;

import java.util.*;

/**
 * FCI-003: question-relative semantic progress, not an empirical truth score.
 * A question is introduced by InterrogativeUniverse.ask(), NEVER by this class.
 * Each compatible valuation induces an answer signature (truth values of all
 * requested facets). Two logically equivalent facet presentations induce the
 * same distinct answer signatures, despite duplicated or equivalent facets.
 * No learned parameters, probabilities or external libraries.
 */
public final class QuestionSemantics {
    private QuestionSemantics() {}

    public enum Status { EVALUATED, INCONSISTENT, RESOURCE_LIMIT }

    /** Indices refer to the original response.claims() list (zero-based). */
    public record Assessment(Status status, int possibleAnswersBefore, int possibleAnswersAfter,
                             double answerGainBits, boolean questionDetermined,
                             List<Integer> nonredundantSupportIndices,
                             List<Double> marginalBits, String explanation) {
        public Assessment {
            nonredundantSupportIndices = List.copyOf(nonredundantSupportIndices);
            marginalBits = List.copyOf(marginalBits);
        }
    }

    public static Assessment assess(Collection<Formula> context, QuestionGap.Question question,
                                    QuestionGap.Response response, ModelExplorer.Budget budget) {
        Objects.requireNonNull(context); Objects.requireNonNull(question);
        Objects.requireNonNull(response); Objects.requireNonNull(budget);
        List<Formula> premiseList = List.copyOf(context);
        List<Formula> added = new ArrayList<>();
        List<Integer> claimIndices = new ArrayList<>();
        List<Formula> extra = new ArrayList<>();
        for (QuestionGap.Facet f : question.facets()) extra.add(f.target());
        for (int i = 0; i < response.claims().size(); i++) {
            QuestionGap.Claim claim = response.claims().get(i);
            if (claim.admission() == QuestionGap.Admission.ADMITTED) {
                added.add(claim.proposition());
                claimIndices.add(i);
                extra.add(claim.proposition());
            }
        }
        // Both BEFORE and AFTER are evaluated in exactly the same atom universe.
        ModelExplorer.Analysis base = ModelExplorer.analyzeFull(premiseList,
                question.facets().get(0).target(), extra, budget);
        if (base.status() == ModelExplorer.Status.RESOURCE_LIMIT)
            return invalid(Status.RESOURCE_LIMIT, "Enumeration budget exceeded");
        if (base.status() == ModelExplorer.Status.INCONSISTENT)
            return invalid(Status.INCONSISTENT, "Initial premises inconsistent");

        List<ModelExplorer.World> worlds = base.possibleWorlds();
        Set<String> before = signatures(worlds, question.facets());
        List<ModelExplorer.World> afterWorlds = select(worlds, added);
        if (afterWorlds.isEmpty())
            return invalid(Status.INCONSISTENT, "Admitted claims contradict the initial context");
        Set<String> after = signatures(afterWorlds, question.facets());

        // Ablation assesses only CONDITIONAL question progress; it cannot certify
        // truth, provenance quality, or minimality of all possible proofs.
        List<Double> marginal = new ArrayList<>();
        for (int i = 0; i < added.size(); i++) {
            List<Formula> except = new ArrayList<>(added);
            except.remove(i);
            int without = signatures(select(worlds, except), question.facets()).size();
            marginal.add(log2((double) without / after.size()));
        }
        // Deterministic deletion minimization: an inclusion-minimal (not
        // minimum-cardinality) set of admitted claims preserving question answers.
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < added.size(); i++) remaining.add(i);
        for (int i = 0; i < added.size(); i++) {
            List<Integer> trial = new ArrayList<>(remaining);
            trial.remove(Integer.valueOf(i));
            List<Formula> trialClaims = new ArrayList<>();
            for (int index : trial) trialClaims.add(added.get(index));
            Set<String> trialAnswers = signatures(select(worlds, trialClaims), question.facets());
            if (trialAnswers.equals(after)) remaining = trial;
        }
        List<Integer> support = new ArrayList<>();
        for (int index : remaining) support.add(claimIndices.get(index));

        return new Assessment(Status.EVALUATED, before.size(), after.size(),
                log2((double) before.size() / after.size()), after.size() == 1,
                support, marginal,
                "Question-relative combinatorial reduction only; admitted claims "
                + "are assumptions, not verified observations. A question act and "
                + "an explicit justified answer are still required to repair f_Q.");
    }

    private static Assessment invalid(Status status, String explanation) {
        return new Assessment(status, 0, 0, Double.NaN, false,
                List.of(), List.of(), explanation);
    }

    private static List<ModelExplorer.World> select(List<ModelExplorer.World> worlds,
                                                    List<Formula> claims) {
        List<ModelExplorer.World> filtered = new ArrayList<>();
        outer: for (ModelExplorer.World w : worlds) {
            for (Formula c : claims) if (!w.satisfies(c)) continue outer;
            filtered.add(w);
        }
        return filtered;
    }

    private static Set<String> signatures(List<ModelExplorer.World> worlds,
                                          List<QuestionGap.Facet> facets) {
        Set<String> answers = new HashSet<>();
        for (ModelExplorer.World world : worlds) {
            StringBuilder bits = new StringBuilder(facets.size());
            for (QuestionGap.Facet f : facets) bits.append(world.satisfies(f.target()) ? '1' : '0');
            answers.add(bits.toString());
        }
        return answers;
    }

    private static double log2(double v) { return Math.log(v) / Math.log(2); }
}
