package fr.ivorama.cognitive;

import java.util.*;

/**
 * IVOC-FND-004-PROV: proof-carrying source lineage. A textual source is an
 * untrusted attribution, NEVER an independently verified empirical fact.
 * Formula dependencies are conjunctive; sources of an identical formula are
 * alternative active sources. No amount of deduction can raise empirical trust.
 */
public final class ProvenanceAudit {
    private ProvenanceAudit() {}

    public enum Basis {
        FORMAL_THEOREM,          // fully checked derivation with no external assumptions
        PROOF_CONDITIONAL,      // checked derivation with unverified premises
        DIRECT_UNVERIFIED,      // premise restates the requested answer, with no independent proof found
        MODEL_ONLY_CONDITIONAL, // exhaustive finite semantic entailment, no syntactic proof certificate
        NOT_ESTABLISHED
    }

    public record Source(String id, String claimedSource, KnowledgeBase.Kind kind) {
        public Source {
            Objects.requireNonNull(id);
            Objects.requireNonNull(claimedSource);
            Objects.requireNonNull(kind);
        }
    }
    /** All formula dependencies are required; source IDs within each group are alternatives. */
    public record Dependency(Formula formula, List<Source> alternativeActiveSources) {
        public Dependency {
            Objects.requireNonNull(formula);
            alternativeActiveSources = List.copyOf(alternativeActiveSources);
            if (alternativeActiveSources.isEmpty())
                throw new IllegalArgumentException("Dependency without active sources");
        }
    }
    /** One minimal *semantic* support: every dependency is required, each source list is alternative.
     * A semantic support is NOT a syntactic proof and carries no empirical verification.
     */
    public record SemanticSupport(List<Dependency> requiredPremises) {
        public SemanticSupport {
            requiredPremises = List.copyOf(requiredPremises);
            Set<Formula> unique = new HashSet<>();
            for (Dependency d : requiredPremises)
                if (!unique.add(d.formula())) throw new IllegalArgumentException("Duplicate semantic support premise");
        }
        public Set<Formula> formulas() {
            Set<Formula> out = new LinkedHashSet<>();
            for (Dependency d : requiredPremises) out.add(d.formula());
            return Set.copyOf(out);
        }
    }
    public record Certificate(Formula goal, Basis basis, boolean proofChecked,
                              Optional<Proof> formalProof, List<Dependency> dependencies,
                              long version, String limitation, SupportAnalyzer.Result alternativeSupports,
                              Optional<List<SemanticSupport>> semanticSupportLineage) {
        /** Legacy constructor: existing callers may supply syntactic evidence only.
         * Absence of a lineage snapshot means *not recorded*, never zero sources.
         */
        public Certificate(Formula goal, Basis basis, boolean proofChecked,
                           Optional<Proof> formalProof, List<Dependency> dependencies,
                           long version, String limitation, SupportAnalyzer.Result alternativeSupports) {
            this(goal,basis,proofChecked,formalProof,dependencies,version,limitation,alternativeSupports,
                    Optional.empty());
        }
        public Certificate {
            Objects.requireNonNull(goal);
            Objects.requireNonNull(basis);
            Objects.requireNonNull(formalProof);
            dependencies = List.copyOf(dependencies);
            Objects.requireNonNull(limitation);
            Objects.requireNonNull(alternativeSupports);
            Objects.requireNonNull(semanticSupportLineage);
            semanticSupportLineage = semanticSupportLineage.map(List::copyOf);
            if (semanticSupportLineage.isPresent()) {
                // Only semantic supports already established by the bounded support search
                // can be paired with source attribution; no new logical claim is admitted.
                List<SemanticSupport> snapshots = semanticSupportLineage.orElseThrow();
                Set<Set<Formula>> claimed = new HashSet<>();
                for (SemanticSupport one : snapshots)
                    if (!claimed.add(one.formulas()))
                        throw new IllegalArgumentException("Duplicate semantic support");
                Set<Set<Formula>> expected = new HashSet<>(alternativeSupports.minimalSupports());
                if (claimed.size()!=snapshots.size() || !claimed.equals(expected))
                    throw new IllegalArgumentException("Semantic lineage must match calculated supports");
            }

            // A certificate's public constructor must enforce the same evidence
            // invariants as inspect(): callers may not assert a proof by setting
            // a boolean or a basis label alone.
            if (proofChecked != formalProof.isPresent())
                throw new IllegalArgumentException("proofChecked and formalProof must agree");
            if (basis == Basis.FORMAL_THEOREM) {
                if (!proofChecked || !dependencies.isEmpty())
                    throw new IllegalArgumentException("Formal theorem requires a checked assumption-free proof");
            } else if (basis == Basis.PROOF_CONDITIONAL) {
                if (!proofChecked || dependencies.isEmpty())
                    throw new IllegalArgumentException("Conditional proof requires a checked proof and dependencies");
            } else if (basis == Basis.DIRECT_UNVERIFIED) {
                if (dependencies.stream().noneMatch(d -> d.formula().equals(goal)))
                    throw new IllegalArgumentException("Direct assertion must cite the goal as a dependency");
            } else {
                if (proofChecked || (basis == Basis.NOT_ESTABLISHED && !dependencies.isEmpty()))
                    throw new IllegalArgumentException("Unproven state cannot carry checked proof evidence");
            }

            // Reconstruct the *minimal claimed context* and independently
            // check the proof. This also rejects forged conclusions and
            // deliberately omitted proof dependencies.
            if (proofChecked) {
                Set<Formula> claimed = new HashSet<>();
                for (Dependency dependency : dependencies) {
                    if (!claimed.add(dependency.formula()))
                        throw new IllegalArgumentException("Duplicate proof dependency");
                }
                ProofChecker.Result verified = ProofChecker.verify(formalProof.orElseThrow(), claimed);
                if (!verified.valid() || !goal.equals(verified.conclusion())
                        || !claimed.equals(verified.dependencies()))
                    throw new IllegalArgumentException("Certificate proof does not justify goal and dependencies");
            }
        }
        /** This is a theorem of the calculus, NOT an empirical validation of world facts. */
        public boolean hasPremiseFreeFormalCertificate() {
            return basis == Basis.FORMAL_THEOREM && proofChecked
                    && formalProof.isPresent() && dependencies.isEmpty();
        }
        public boolean hasEmpiricallyVerifiedPremises() { return false; }

        /** Never report zero sources just because no *syntactic* proof was found. */
        public String semanticSupportSummary() {
            if (semanticSupportLineage.isEmpty())
                return "Supports sémantiques : provenance non capturée (pas une absence de sources).";
            List<SemanticSupport> supports = semanticSupportLineage.orElseThrow();
            if (supports.isEmpty())
                return "Supports sémantiques : aucun ensemble identifié (" + alternativeSupports.status()
                        + ") ; ne pas interpréter comme zéro source.";
            long premiseFree = supports.stream().filter(s -> s.requiredPremises().isEmpty()).count();
            String qualifier = alternativeSupports.status() == SupportAnalyzer.Status.COMPLETE
                    ? "énumération complète" : "résultat partiel : " + alternativeSupports.status();
            return "Supports sémantiques : " + supports.size() + " ensemble(s) minimal(aux), "
                    + qualifier + (premiseFree > 0 ? " ; sans prémisse externe : " + premiseFree : "")
                    + " (sources déclarées, non certifiées).";
        }
    }

    /** Requires the caller's independently confirmed finite-model entailment. */
    public static Certificate inspect(Formula goal, Collection<Formula> active,
                                      List<KnowledgeBase.Premise> history,
                                      long version, int proofBudget, ModelExplorer.Budget modelBudget) {
        Objects.requireNonNull(goal);
        Objects.requireNonNull(active);
        Objects.requireNonNull(history);
        Objects.requireNonNull(modelBudget);
        if (proofBudget < 1) throw new IllegalArgumentException("Positive proof budget required");
        Set<Formula> context = Set.copyOf(active);
        // One independent semantic determination per audit. The bounded support
        // enumerator shares the result, without trusting a caller-supplied verdict.
        ModelExplorer.Analysis entailment = ModelExplorer.analyze(context, goal, modelBudget);
        SupportAnalyzer.Result supportAnalysis = SupportAnalyzer.findWithEntailment(
                context, goal, modelBudget, 2048, 64, entailment);
        // Capture the source lineage AT THIS KNOWLEDGE VERSION; later retractions must
        // create a different certificate and must not rewrite this historical snapshot.
        Optional<List<SemanticSupport>> semanticLineage = Optional.of(
                semanticLineage(supportAnalysis, history));
        // A certificate must never be minted from a caller's unsupported claim
        // that a conclusion follows. Verify entailment and consistency first.
        if (entailment.status() != ModelExplorer.Status.PROVEN_TRUE) {
            return new Certificate(goal, Basis.NOT_ESTABLISHED, false, Optional.empty(),
                    List.of(), version,
                    "Aucun appui certifié : contexte incohérent, non concluant ou hors budget.",
                    supportAnalysis, semanticLineage);
        }
        // First try to establish a theorem with no assumption whatsoever.
        Optional<Proof> found = ProofSearch.find(Set.of(), goal, proofBudget);
        // Avoid the direct-answer shortcut if other assumptions suffice.
        if (found.isEmpty() && context.contains(goal)) {
            Set<Formula> other = new LinkedHashSet<>(context);
            other.remove(goal);
            found = ProofSearch.find(other, goal, proofBudget);
        }
        if (found.isEmpty()) found = ProofSearch.find(context, goal, proofBudget);

        if (found.isPresent()) {
            Proof proof = found.orElseThrow();
            ProofChecker.Result checked = ProofChecker.verify(proof, context);
            if (checked.valid() && goal.equals(checked.conclusion())) {
                List<Dependency> required = lineage(checked.dependencies(), history);
                Basis basis = checked.dependencies().isEmpty() ? Basis.FORMAL_THEOREM
                        : checked.dependencies().contains(goal) ? Basis.DIRECT_UNVERIFIED
                        : Basis.PROOF_CONDITIONAL;
                return new Certificate(goal, basis, true, Optional.of(proof), required,
                        version, basis == Basis.FORMAL_THEOREM
                            ? "Théorème formel sans prémisse externe ; aucune portée empirique implicite."
                            : "Preuve valide mais sources déclaratives non vérifiées empiriquement.",
                        supportAnalysis, semanticLineage);
            }
        }
        return new Certificate(goal,
                context.contains(goal) ? Basis.DIRECT_UNVERIFIED : Basis.MODEL_ONLY_CONDITIONAL,
                false, Optional.empty(), context.contains(goal)
                        ? lineage(Set.of(goal), history) : List.of(), version,
                "Conséquence sur modèles finis, mais aucun certificat de preuve vérifié produit. "
                + "Les sources textuelles restent non certifiées.", supportAnalysis, semanticLineage);
    }

    private static List<SemanticSupport> semanticLineage(SupportAnalyzer.Result support,
                                                         List<KnowledgeBase.Premise> history) {
        List<SemanticSupport> groups = new ArrayList<>();
        for (Set<Formula> formulas : support.minimalSupports())
            groups.add(new SemanticSupport(lineage(formulas, history)));
        return List.copyOf(groups);
    }

    private static List<Dependency> lineage(Set<Formula> dependencies,
                                            List<KnowledgeBase.Premise> history) {
        List<Dependency> result = new ArrayList<>();
        for (Formula f : dependencies) {
            List<Source> alternatives = new ArrayList<>();
            for (KnowledgeBase.Premise p : history) {
                if (p.active() && p.formula().equals(f))
                    alternatives.add(new Source(p.id(), p.source(), p.kind()));
            }
            result.add(new Dependency(f, alternatives));
        }
        result.sort(Comparator.comparing(d -> d.formula().toString()));
        return List.copyOf(result);
    }
}
