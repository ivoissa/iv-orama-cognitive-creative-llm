package fr.ivorama.cognitive;

import java.util.*;

/**
 * P2.3a: explicit, read-only, budgeted proof-route experiment for a decided FCI question.
 * This diagnostic does NOT change a question's verdict, its certificate, or its state.
 * SAT explanations are candidate hints, not proofs; ProofChecker remains authoritative
 * for a constructed syntactic proof. No LLM backend is required.
 */
public final class P23ProofRouting {
    private P23ProofRouting() {}

    public record Options(int maxDerived, long maxSearchOperations,
                          int maxSupportAttempts, int maxPremises) {
        public Options {
            if (maxDerived < 1 || maxDerived > 2048 || maxSearchOperations < 1
                    || maxSearchOperations > 2_000_000L || maxSupportAttempts < 0
                    || maxSupportAttempts > 32 || maxPremises < 1 || maxPremises > 128)
                throw new IllegalArgumentException("Invalid P2.3 proof-routing budgets");
        }
        public static Options conservative() { return new Options(200, 10_000, 2, 64); }
    }

    public enum Route { GUIDED, INDEXED_FALLBACK, SKIPPED_PREMISE_LIMIT, RESOURCE_LIMIT, NO_PROOF }

    public record Receipt(Formula goal, long knowledgeVersion,
                          ProvenanceAudit.Basis baselineBasis,
                          Route route, GuidedProofSearch.GuidanceStatus guidance,
                          ProofSearch.SearchStatus searchStatus,
                          Optional<Proof> proof, boolean proofChecked,
                          Set<Formula> proofDependencies,
                          List<String> selectedSupportIds, long proofOperations,
                          int examinedSupports, boolean fallbackUsed,
                          List<SemanticExplanation.Premise> contextSnapshot,
                          String limitation) {
        public Receipt {
            Objects.requireNonNull(goal); Objects.requireNonNull(baselineBasis);
            Objects.requireNonNull(route); Objects.requireNonNull(guidance);
            Objects.requireNonNull(searchStatus); Objects.requireNonNull(proof);
            Objects.requireNonNull(limitation);
            proofDependencies = Set.copyOf(proofDependencies);
            selectedSupportIds = List.copyOf(selectedSupportIds);
            contextSnapshot = List.copyOf(contextSnapshot);
            if (knowledgeVersion < 0 || proofOperations < 0 || examinedSupports < 0)
                throw new IllegalArgumentException("Negative receipt metrics");
            if (proofChecked != proof.isPresent()
                    || (searchStatus == ProofSearch.SearchStatus.FOUND) != proofChecked)
                throw new IllegalArgumentException("Unverified or inconsistent routed proof");
            if (proofChecked) {
                var checked = ProofChecker.verify(proof.orElseThrow(),
                        contextSnapshot.stream().map(SemanticExplanation.Premise::formula).toList());
                if (!checked.valid() || !goal.equals(checked.conclusion())
                        || !proofDependencies.equals(checked.dependencies()))
                    throw new IllegalArgumentException("Routed proof does not justify receipt goal/dependencies");
            } else if (!proofDependencies.isEmpty() || !selectedSupportIds.isEmpty()) {
                throw new IllegalArgumentException("Unproved routing receipt cannot claim dependencies/supports");
            }
        }

        /** Includes knowledge version, active premise identifiers, formulas, sources and goal. */
        public boolean isCurrent(long version,
                                 Collection<SemanticExplanation.Premise> active, Formula currentGoal) {
            return version == knowledgeVersion && goal.equals(currentGoal)
                    && contextSnapshot.equals(List.copyOf(active));
        }
        public boolean isAdditionalCheckedProof() {
            return proofChecked && !proofDependencies.contains(goal)
                    && baselineBasis != ProvenanceAudit.Basis.FORMAL_THEOREM
                    && baselineBasis != ProvenanceAudit.Basis.PROOF_CONDITIONAL;
        }
    }

    /** The baseline certificate must already belong to this context and goal. */
    public static Receipt inspect(SemanticExplanationProvider provider,
                                  Collection<SemanticExplanation.Premise> premises,
                                  Formula goal, long version,
                                  ProvenanceAudit.Certificate baseline,
                                  Options options) {
        Objects.requireNonNull(provider); Objects.requireNonNull(premises);
        Objects.requireNonNull(goal); Objects.requireNonNull(baseline);
        Objects.requireNonNull(options);
        List<SemanticExplanation.Premise> snapshot = List.copyOf(premises);
        if (version != baseline.version() || !goal.equals(baseline.goal())
                || baseline.basis() == ProvenanceAudit.Basis.NOT_ESTABLISHED)
            throw new IllegalArgumentException("Not a current, established baseline certificate");
        Set<String> ids = new HashSet<>();
        for (var p : snapshot)
            if (!ids.add(p.id())) throw new IllegalArgumentException("Duplicate active premise ID");

        var formulas = snapshot.stream().map(SemanticExplanation.Premise::formula).toList();
        if (snapshot.size() > options.maxPremises()) {
            return new Receipt(goal,version,baseline.basis(),Route.SKIPPED_PREMISE_LIMIT,
                    GuidedProofSearch.GuidanceStatus.SKIPPED,ProofSearch.SearchStatus.RESOURCE_LIMIT,
                    Optional.empty(),false,Set.of(),List.of(),0,0,false,snapshot,
                    "P2.3 experiment skipped: premise count exceeds configured bound");
        }
        GuidedProofSearch.Result routed = GuidedProofSearch.find(provider, snapshot, goal,
                options.maxDerived(),options.maxSearchOperations(),options.maxSupportAttempts());
        var result = routed.outcome();
        if (result.proof().isPresent()) {
            // Independent second verification, even if the indexed or guided search already checked.
            var check = ProofChecker.verify(result.proof().orElseThrow(), formulas);
            if (!check.valid() || !goal.equals(check.conclusion()))
                throw new IllegalStateException("Proof routing produced an invalid proof");
            return new Receipt(goal,version,baseline.basis(),
                    routed.guidance() == GuidedProofSearch.GuidanceStatus.USED
                            ? Route.GUIDED : Route.INDEXED_FALLBACK,
                    routed.guidance(),result.status(),result.proof(),true,check.dependencies(),
                    routed.selectedSupportIds(),result.operations(),routed.examinedSupports(),
                    routed.fallbackUsed(),snapshot,
                    "Optional checked syntactic proof; baseline FCI answer unchanged");
        }
        return new Receipt(goal,version,baseline.basis(),
                result.status() == ProofSearch.SearchStatus.RESOURCE_LIMIT
                        ? Route.RESOURCE_LIMIT : Route.NO_PROOF,
                routed.guidance(),result.status(),Optional.empty(),false,Set.of(),List.of(),
                result.operations(),routed.examinedSupports(),routed.fallbackUsed(),snapshot,
                "No additional checked proof. Baseline verdict and evidence remain authoritative");
    }
}
