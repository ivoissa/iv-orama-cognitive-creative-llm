package fr.ivorama.cognitive;

import java.util.*;

/**
 * Recherche exhaustive BORNÉE des ensembles de prémisses minimaux PAR INCLUSION.
 * Les supports sont sémantiques et NE valent pas certificats de preuves syntaxiques.
 * Un résultat incomplet est explicitement signalé (jamais « tous les supports »).
 */
public final class SupportAnalyzer {
    private SupportAnalyzer() {}
    public enum Status { COMPLETE, NOT_ENTAILED, INCONSISTENT, RESOURCE_LIMIT, SEARCH_LIMIT }
    public record Result(Status status, List<Set<Formula>> minimalSupports, int examinedSubsets, String explanation) {
        public Result {
            Objects.requireNonNull(status);
            minimalSupports = minimalSupports.stream().map(Set::copyOf).toList();
            Objects.requireNonNull(explanation);
        }
        public boolean exhaustive() { return status == Status.COMPLETE; }
    }

    public static Result find(Collection<Formula> premises, Formula goal,
                              ModelExplorer.Budget budget, int maxSubsets, int maxSupports) {
        Objects.requireNonNull(premises); Objects.requireNonNull(goal); Objects.requireNonNull(budget);
        if (maxSubsets < 1 || maxSupports < 1) throw new IllegalArgumentException("Positive limits required");
        // Standalone entry point remains self-verifying.
        ModelExplorer.Analysis full = ModelExplorer.analyze(premises, goal, budget);
        return findWithEntailment(premises, goal, budget, maxSubsets, maxSupports, full);
    }

    /** Package-internal: the audit has already called ModelExplorer on this exact
     * snapshot. Never exposed as a public trust-bypassing entry point.
     */
    static Result findWithEntailment(Collection<Formula> premises, Formula goal,
                                    ModelExplorer.Budget budget, int maxSubsets, int maxSupports,
                                    ModelExplorer.Analysis full) {
        Objects.requireNonNull(full);
        if (maxSubsets < 1 || maxSupports < 1) throw new IllegalArgumentException("Positive limits required");
        List<Formula> active = new ArrayList<>(new LinkedHashSet<>(premises));
        active.sort(Comparator.comparing(Formula::toString));
        if (full.status() == ModelExplorer.Status.INCONSISTENT) return result(Status.INCONSISTENT, List.of(), 0);
        if (full.status() == ModelExplorer.Status.RESOURCE_LIMIT) return result(Status.RESOURCE_LIMIT, List.of(), 0);
        if (full.status() != ModelExplorer.Status.PROVEN_TRUE) return result(Status.NOT_ENTAILED, List.of(), 0);
        if (active.size() >= 31) return result(Status.SEARCH_LIMIT, List.of(), 0);
        List<Set<Formula>> minima = new ArrayList<>();
        int examined = 0;
        int n = active.size();
        // Increasing cardinality ensures supersets of earlier minima need no check.
        // P0.3: iterate only masks of the desired cardinality. The previous
        // implementation scanned all 2^n masks for every size (up to n=30).
        // Covered supersets still cost enumeration work: cap them separately.
        long maxEnumerations = Math.max(65_536L, (long) maxSubsets * 8L);
        long enumerated = 0;
        long limit = 1L << n;
        for (int size=0; size<=n; size++) {
            long initial = size == 0 ? 0 : (1L << size) - 1;
            for (long mask=initial; mask<limit; mask=nextCombination(mask)) {
                if (++enumerated > maxEnumerations)
                    return result(Status.SEARCH_LIMIT, minima, examined);
                Set<Formula> candidate = new LinkedHashSet<>();
                for (int j=0;j<n;j++) if ((mask & (1 << j)) != 0) candidate.add(active.get(j));
                boolean covered=false;
                for (Set<Formula> m : minima) if (candidate.containsAll(m)) { covered=true; break; }
                if (covered) continue;
                if (examined >= maxSubsets) return result(Status.SEARCH_LIMIT, minima, examined);
                examined++;
                var analysis = ModelExplorer.analyze(candidate, goal, budget);
                if (analysis.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                    return result(Status.RESOURCE_LIMIT, minima, examined);
                if (analysis.status() == ModelExplorer.Status.PROVEN_TRUE) {
                    minima.add(Set.copyOf(candidate));
                    if (candidate.isEmpty()) // The theorem has exactly one inclusion-minimal support.
                        return result(Status.COMPLETE, minima, examined);
                    if (minima.size() >= maxSupports) {
                        // The result may omit other minimal supports; never label complete.
                        return result(Status.SEARCH_LIMIT, minima, examined);
                    }
                }
            }
        }
        return result(Status.COMPLETE, minima, examined);
    }
    /** Gosper's next-mask operation, positive after the size=0 singleton. */
    private static long nextCombination(long mask) {
        if (mask == 0) return Long.MAX_VALUE;
        long low = mask & -mask;
        long rise = mask + low;
        return rise | (((rise ^ mask) >>> 2) / low);
    }
    private static Result result(Status s, List<Set<Formula>> m, int count) {
        return new Result(s, m, count, "Support search " + s + ", checked " + count + " subsets");
    }
}
