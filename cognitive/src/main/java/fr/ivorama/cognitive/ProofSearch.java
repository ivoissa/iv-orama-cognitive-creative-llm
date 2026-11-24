package fr.ivorama.cognitive;

import java.util.*;

/** Recherche bornée (incomplète) : ∧-élimination puis modus ponens. */
public final class ProofSearch {
    private ProofSearch() {}
    /** Global work ceiling shared by every recursive branch of a search. */
    public static final long DEFAULT_MAX_OPERATIONS = 1_000_000L;
    public enum SearchStatus { FOUND, NOT_FOUND, RESOURCE_LIMIT }
    public record SearchOutcome(SearchStatus status, Optional<Proof> proof, long operations) {
        public SearchOutcome {
            Objects.requireNonNull(status);
            Objects.requireNonNull(proof);
            if ((status == SearchStatus.FOUND) != proof.isPresent() || operations < 0)
                throw new IllegalArgumentException("Incoherent proof search outcome");
        }
    }
    private static final class SearchBudget {
        final long maximum;
        long used;
        boolean capacityReached;
        SearchBudget(long maximum) { this.maximum = maximum; }
        void tick() {
            if (used >= maximum) throw new SearchLimitReached();
            used++;
        }
    }
    private static final class SearchLimitReached extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
    public static Optional<Proof> find(Collection<Formula> premises, Formula goal, int maxDerived) {
        Objects.requireNonNull(premises); Objects.requireNonNull(goal);
        if (maxDerived < 1) throw new IllegalArgumentException("maxDerived must be positive");
        return findWithBudget(premises, goal, maxDerived, DEFAULT_MAX_OPERATIONS).proof();
    }

    /** Experimental work-list strategy. The historical find/findWithBudget default is unchanged.
     * Every returned term is independently checked against this invocation's premises and goal.
     */
    public static SearchOutcome findIndexedWithBudget(Collection<Formula> premises, Formula goal,
                                                       int maxDerived, long maxOperations) {
        return IndexedProofSearch.find(premises, goal, maxDerived, maxOperations);
    }

    /** Diagnostic timings only; FOUND still requires the independent checker. */
    public record MeasuredSearch(SearchOutcome outcome, long searchNanos, long verificationNanos) {
        public MeasuredSearch {
            Objects.requireNonNull(outcome);
            if(searchNanos<0 || verificationNanos<0) throw new IllegalArgumentException("Negative timing");
        }
    }
    public static MeasuredSearch measureIndexedWithBudget(Collection<Formula> premises, Formula goal,
                                                          int maxDerived, long maxOperations) {
        return IndexedProofSearch.measure(premises,goal,maxDerived,maxOperations);
    }

    /** Does not mistake resource exhaustion for a failed logical implication. */
    public static SearchOutcome findWithBudget(Collection<Formula> premises, Formula goal,
                                               int maxDerived, long maxOperations) {
        Objects.requireNonNull(premises); Objects.requireNonNull(goal);
        if (maxDerived < 1 || maxOperations < 1)
            throw new IllegalArgumentException("Positive proof-search budgets required");
        SearchBudget work = new SearchBudget(maxOperations);
        try {
            Optional<Proof> found = findWithin(premises, goal, maxDerived, 0, work);
            return new SearchOutcome(found.isPresent() ? SearchStatus.FOUND :
                    work.capacityReached ? SearchStatus.RESOURCE_LIMIT : SearchStatus.NOT_FOUND,
                    found, work.used);
        } catch (SearchLimitReached exhausted) {
            return new SearchOutcome(SearchStatus.RESOURCE_LIMIT, Optional.empty(), work.used);
        }
    }

    /** Recursion bornée d'introduction de l'implication (notamment négation). */
    private static Optional<Proof> findWithin(Collection<Formula> premises, Formula goal,
                                              int maxDerived, int depth, SearchBudget work) {
        work.tick();
        Map<Formula, Proof> known = new LinkedHashMap<>();
        for (Formula f : premises) {
            work.tick();
            if (known.size() == maxDerived && !known.containsKey(f)) {
                work.capacityReached = true;
                return Optional.empty();
            }
            known.putIfAbsent(f, new Proof.Assumption(f));
        }
        if (known.containsKey(goal)) return Optional.of(known.get(goal));
        boolean changed = true;
        while (changed && known.size() < maxDerived) {
            changed = false;
            List<Map.Entry<Formula,Proof>> snapshot = List.copyOf(known.entrySet());
            for (Map.Entry<Formula,Proof> entry : snapshot) {
                work.tick();
                Formula f = entry.getKey();
                if (f instanceof Formula.And a) {
                    changed |= add(known, a.left(), new Proof.AndLeft(entry.getValue()), maxDerived, work);
                    if (known.containsKey(goal)) return Optional.of(known.get(goal));
                    changed |= add(known, a.right(), new Proof.AndRight(entry.getValue()), maxDerived, work);
                    if (known.containsKey(goal)) return Optional.of(known.get(goal));
                }
                if (f instanceof Formula.Implies arrow) {
                    Proof antecedent = known.get(arrow.premise());
                    if (antecedent != null) {
                        changed |= add(known, arrow.conclusion(),
                                new Proof.ModusPonens(antecedent, entry.getValue()), maxDerived, work);
                        if (known.containsKey(goal)) return Optional.of(known.get(goal));
                    }
                }
                if (known.size() >= maxDerived) break;
            }
        }
        // Compositionnalité logique : pour répondre à « p et q ? », construire
        // une preuve avec ∧-introduction, et la faire vérifier indépendamment.
        if (depth < 8 && goal instanceof Formula.And both) {
            Optional<Proof> left=findWithin(premises,both.left(),maxDerived,depth+1,work);
            if(left.isPresent()) {
                Optional<Proof> right=findWithin(premises,both.right(),maxDerived,depth+1,work);
                if(right.isPresent()) {
                    Proof candidate=new Proof.AndIntroduction(left.orElseThrow(),right.orElseThrow());
                    var checked=ProofChecker.verify(candidate,premises);
                    if(checked.valid() && goal.equals(checked.conclusion())) return Optional.of(candidate);
                }
            }
        }
        // Backward proof search: to prove a -> b, temporarily assume a and
        // prove b. Do not reuse a proof without independent verification.
        if (depth < 4 && goal instanceof Formula.Implies arrow) {
            Set<Formula> expanded = new LinkedHashSet<>(premises);
            expanded.add(arrow.premise());
            Optional<Proof> body = findWithin(expanded, arrow.conclusion(), maxDerived, depth + 1, work);
            if (body.isPresent()) {
                Proof candidate = new Proof.ImplicationIntroduction(arrow.premise(), body.orElseThrow());
                var checked = ProofChecker.verify(candidate, premises);
                if (checked.valid() && goal.equals(checked.conclusion())) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
    private static boolean add(Map<Formula,Proof> proofs, Formula f, Proof proof,
                               int max, SearchBudget work) {
        if (proofs.containsKey(f)) return false;
        if (proofs.size() >= max) {
            work.capacityReached = true;
            return false;
        }
        proofs.put(f, proof);
        return true;
    }
}
