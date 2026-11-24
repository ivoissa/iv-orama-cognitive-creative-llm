package fr.ivorama.cognitive;

import java.util.*;

/** Reprise déterminative orientée : sélection d'un test observable discriminant. */
public final class RdoPlanner {
    private RdoPlanner() {}

    public record Candidate(Formula observation, int cost) {
        public Candidate {
            Objects.requireNonNull(observation);
            if (cost <= 0) throw new IllegalArgumentException("cost must be positive");
        }
    }

    public record Choice(Candidate candidate, int guaranteesQuestion, int worstRemainingWorlds,
                         int possibleTrue, int possibleFalse) {}

    public record Plan(ModelExplorer.Analysis analysis, Optional<Choice> choice,
                       List<Candidate> plannedSequence, String explanation) {
        public Plan { plannedSequence = List.copyOf(plannedSequence); }
    }
    private static final int MAX_LOOKAHEAD_DEPTH = 3;
    private static final int MAX_PLANNING_STATES = 1000;

    public static Plan propose(Collection<Formula> premises, Formula question,
                               List<Candidate> observables, ModelExplorer.Budget budget) {
        Objects.requireNonNull(observables);
        List<Formula> extra = observables.stream().map(Candidate::observation).toList();
        ModelExplorer.Analysis analysis = ModelExplorer.analyze(premises, question, extra, budget);
        if (analysis.status() != ModelExplorer.Status.UNDETERMINED)
            return new Plan(analysis, Optional.empty(), List.of(), "No investigation: " + analysis.status());
        // RDO needs worlds, but only for the question and candidate observations.
        // First check all premise components for consistency above, then enumerate
        // the relevant slice. Never present these worlds as global possibleWorlds.
        ModelExplorer.Analysis local = analysis;
        if (!analysis.completeWorlds()) {
            List<Formula> targets = new ArrayList<>(extra);
            targets.add(question);
            List<Formula> localPremises = ModelExplorer.relevantPremises(premises, targets);
            local = ModelExplorer.analyzeFull(localPremises, question, extra, budget);
            if (local.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                return new Plan(local, Optional.empty(), List.of(),
                        "Relevant RDO world enumeration exceeds budget; no plan certified");
        }
        List<ModelExplorer.World> worlds = local.possibleWorlds();

        // Find a minimal set of observations whose complete outcomes determine Q.
        // A single observation may have zero immediate question gain (e.g. parity),
        // while a bounded sequence resolves Q. Search counts are explicitly capped.
        List<Candidate> viable = new ArrayList<>();
        for (Candidate candidate : observables) {
            boolean yes = false, no = false;
            for (ModelExplorer.World w : worlds) {
                if (w.satisfies(candidate.observation())) yes = true; else no = true;
            }
            if (yes && no) viable.add(candidate);
        }
        List<Candidate> sequence = findGuaranteedSequence(worlds, question, viable);
        if (!sequence.isEmpty()) {
            Candidate first = sequence.get(0);
            Choice choice = evaluateChoice(worlds, question, first);
            return new Plan(analysis, Optional.of(choice), sequence,
                    "Plan RDO garanti en <= " + sequence.size() + " observations, sous hypothèses ; "
                    + "non garanti si une valeur reste indisponible");
        }
        Choice best = null;
        for (Candidate observable : observables) {
            List<ModelExplorer.World> yes = new ArrayList<>();
            List<ModelExplorer.World> no = new ArrayList<>();
            for (ModelExplorer.World world : worlds) {
                (world.satisfies(observable.observation()) ? yes : no).add(world);
            }
            // A test already determined by K provides no new information.
            if (yes.isEmpty() || no.isEmpty()) continue;
            int guarantee = Math.min(questionGain(yes, question), questionGain(no, question));
            Choice candidate = new Choice(observable, guarantee, Math.max(yes.size(), no.size()), yes.size(), no.size());
            if (best == null || better(candidate, best)) best = candidate;
        }
        return new Plan(analysis, Optional.ofNullable(best), best == null ? List.of() : List.of(best.candidate()),
                best == null ? "No discriminating observable available"
                        : "Greedy exploration only; no guaranteed resolution within lookahead budget");
    }


    private static Choice evaluateChoice(List<ModelExplorer.World> worlds, Formula q, Candidate c) {
        List<ModelExplorer.World> yes = new ArrayList<>(), no = new ArrayList<>();
        for (ModelExplorer.World w : worlds) (w.satisfies(c.observation()) ? yes : no).add(w);
        return new Choice(c, Math.min(questionGain(yes, q), questionGain(no, q)),
                Math.max(yes.size(), no.size()), yes.size(), no.size());
    }

    private static List<Candidate> findGuaranteedSequence(List<ModelExplorer.World> worlds,
            Formula q, List<Candidate> candidates) {
        // Enumerate bounded combinations. Every possible signature must have
        // a unique answer to Q; this is an AND/OR-lookahead plan, not a prediction.
        int[] visited = {0};
        for (int size = 1; size <= Math.min(MAX_LOOKAHEAD_DEPTH, candidates.size()); size++) {
            List<List<Candidate>> valid = new ArrayList<>();
            enumerate(candidates, 0, size, new ArrayList<>(), valid, worlds, q, visited);
            if (!valid.isEmpty()) {
                valid.sort(Comparator.<List<Candidate>>comparingInt(list -> list.stream()
                        .mapToInt(Candidate::cost).sum())
                        .thenComparing(list -> list.stream().map(c -> c.observation().toString())
                                .reduce("", (a,b) -> a + "|" + b)));
                return valid.get(0);
            }
            if (visited[0] >= MAX_PLANNING_STATES) break;
        }
        return List.of();
    }

    private static void enumerate(List<Candidate> candidates, int from, int needed,
            List<Candidate> prefix, List<List<Candidate>> valid, List<ModelExplorer.World> worlds,
            Formula q, int[] visited) {
        if (visited[0] >= MAX_PLANNING_STATES) return;
        if (needed == 0) {
            visited[0]++;
            if (guaranteesAnswer(worlds, q, prefix)) valid.add(List.copyOf(prefix));
            return;
        }
        for (int i = from; i <= candidates.size() - needed; i++) {
            if (visited[0] >= MAX_PLANNING_STATES) return;
            prefix.add(candidates.get(i));
            enumerate(candidates, i + 1, needed - 1, prefix, valid, worlds, q, visited);
            prefix.remove(prefix.size() - 1);
        }
    }

    private static boolean guaranteesAnswer(List<ModelExplorer.World> worlds, Formula q,
                                            List<Candidate> candidates) {
        Map<String, Boolean> outcomeAnswer = new HashMap<>();
        for (ModelExplorer.World w : worlds) {
            StringBuilder key = new StringBuilder();
            for (Candidate c : candidates) key.append(w.satisfies(c.observation()) ? '1' : '0');
            Boolean existing = outcomeAnswer.putIfAbsent(key.toString(), w.satisfies(q));
            if (existing != null && existing != w.satisfies(q)) return false;
        }
        return true;
    }

    private static int questionGain(List<ModelExplorer.World> worlds, Formula q) {
        boolean trueSeen = false, falseSeen = false;
        for (ModelExplorer.World world : worlds) {
            if (world.satisfies(q)) trueSeen = true;
            else falseSeen = true;
            if (trueSeen && falseSeen) return 0;
        }
        return 1;
    }

    private static boolean better(Choice a, Choice b) {
        if (a.guaranteesQuestion() != b.guaranteesQuestion())
            return a.guaranteesQuestion() > b.guaranteesQuestion();
        if (a.worstRemainingWorlds() != b.worstRemainingWorlds())
            return a.worstRemainingWorlds() < b.worstRemainingWorlds();
        if (a.candidate().cost() != b.candidate().cost())
            return a.candidate().cost() < b.candidate().cost();
        return a.candidate().observation().toString().compareTo(b.candidate().observation().toString()) < 0;
    }
}
