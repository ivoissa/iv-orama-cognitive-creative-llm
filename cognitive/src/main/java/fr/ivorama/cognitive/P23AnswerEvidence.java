package fr.ivorama.cognitive;

import java.util.*;

/**
 * P2.3c opt-in, read-only evidence projection for a previously asked question.
 * An optional proof can enrich an explanation but MUST NOT change the FCI verdict,
 * the original ProvenanceAudit certificate, or the claimed empirical reliability.
 * Names and source labels are untrusted attributions, never factual validation.
 */
public final class P23AnswerEvidence {
    private P23AnswerEvidence() {}

    public record Attribution(String premiseId, String declaredSource) {
        public Attribution {
            Objects.requireNonNull(premiseId);
            Objects.requireNonNull(declaredSource);
            if (premiseId.isBlank()) throw new IllegalArgumentException("Empty premise id");
        }
    }

    /** All formula groups are jointly required; sources *within* a group are alternatives. */
    public record DependencyGroup(Formula formula, List<Attribution> alternativeSources) {
        public DependencyGroup {
            Objects.requireNonNull(formula);
            alternativeSources = List.copyOf(alternativeSources);
            if (alternativeSources.isEmpty())
                throw new IllegalArgumentException("Proof dependency without declared attribution");
        }
    }

    public record Evidence(IntegratedQuestionCycle.Report original,
                           Optional<P23ProofRouting.Receipt> optionalRoute,
                           List<DependencyGroup> routedProofDependencies,
                           boolean additionalCheckedProof,
                           String limitation) {
        public Evidence {
            Objects.requireNonNull(original);
            Objects.requireNonNull(optionalRoute);
            routedProofDependencies = List.copyOf(routedProofDependencies);
            Objects.requireNonNull(limitation);
            if (optionalRoute.isEmpty() && (!routedProofDependencies.isEmpty() || additionalCheckedProof))
                throw new IllegalArgumentException("No route can establish a routed proof");
            if (optionalRoute.isPresent()) {
                var receipt = optionalRoute.orElseThrow();
                if (original.answer().isEmpty() || original.certificate().isEmpty())
                    throw new IllegalArgumentException("An undecided question cannot have a proof route");
                Formula goal = original.answer().orElseThrow()
                        ? original.question() : Formula.not(original.question());
                if (receipt.knowledgeVersion() != original.knowledgeVersion()
                        || !receipt.goal().equals(goal)
                        || receipt.baselineBasis() != original.certificate().orElseThrow().basis())
                    throw new IllegalArgumentException("Route does not match the baseline decision");
                if (additionalCheckedProof != receipt.isAdditionalCheckedProof())
                    throw new IllegalArgumentException("Additional proof status must be checked, not asserted");
                Set<Formula> projected = new HashSet<>();
                for (DependencyGroup d : routedProofDependencies) {
                    if (!projected.add(d.formula()))
                        throw new IllegalArgumentException("Duplicate dependency group");
                    List<Attribution> sources = receipt.contextSnapshot().stream()
                            .filter(p -> p.formula().equals(d.formula()))
                            .map(p -> new Attribution(p.id(), p.source()))
                            .sorted(Comparator.comparing(Attribution::premiseId)).toList();
                    if (!sources.equals(d.alternativeSources()))
                        throw new IllegalArgumentException("Untrusted or incomplete source attribution");
                }
                if (!projected.equals(receipt.proofDependencies()))
                    throw new IllegalArgumentException("Proof dependencies are incomplete");
                if (!receipt.proofChecked() && !routedProofDependencies.isEmpty())
                    throw new IllegalArgumentException("No dependency without a checked proof");
            }
        }

        /** Same question, sources, knowledge version, and official certificate are still active. */
        public boolean isCurrent(IntegratedQuestionCycle cycle) {
            Objects.requireNonNull(cycle);
            IntegratedQuestionCycle.Report current;
            try { current = cycle.question(original.id()); }
            catch (IllegalArgumentException ex) { return false; }
            if (!original.equals(current)) return false;
            return optionalRoute.isEmpty() ||
                    cycle.isProofRoutingReceiptCurrent(original.id(), optionalRoute.orElseThrow());
        }
    }

    /** No LLM, no automatic activation, and no mutation of the question state. */
    public static Evidence inspect(IntegratedQuestionCycle cycle, String questionId,
                                   SemanticExplanationProvider provider,
                                   P23ProofRouting.Options options) {
        Objects.requireNonNull(cycle); Objects.requireNonNull(questionId);
        Objects.requireNonNull(provider); Objects.requireNonNull(options);
        var before = cycle.question(questionId);
        var routed = cycle.proofRoutingReceipt(questionId, provider, options);
        var after = cycle.question(questionId);
        if (!before.equals(after))
            throw new IllegalStateException("FCI baseline changed while preparing explanatory evidence");
        if (routed.isEmpty())
            return new Evidence(after, Optional.empty(), List.of(), false,
                    "Aucune réponse établie : aucune preuve facultative invoquée.");

        var receipt = routed.orElseThrow();
        if (!cycle.isProofRoutingReceiptCurrent(questionId, receipt))
            throw new IllegalStateException("Proof evidence is stale");
        List<DependencyGroup> groups = new ArrayList<>();
        for (Formula dependency : receipt.proofDependencies()) {
            var sources = receipt.contextSnapshot().stream()
                    .filter(p -> p.formula().equals(dependency))
                    .map(p -> new Attribution(p.id(), p.source()))
                    .sorted(Comparator.comparing(Attribution::premiseId)).toList();
            groups.add(new DependencyGroup(dependency, sources));
        }
        groups.sort(Comparator.comparing(g -> g.formula().toString()));
        return new Evidence(after, routed, groups, receipt.isAdditionalCheckedProof(),
                receipt.proofChecked()
                        ? "Preuve syntaxique optionnelle vérifiée ; sources seulement déclarées, non validées empiriquement."
                        : "Aucune preuve syntaxique optionnelle ; la conclusion du moteur de référence reste inchangée.");
    }

    /** Human-readable status; deliberately does not embed untrusted source text in instructions. */
    public static String summary(Evidence evidence) {
        Objects.requireNonNull(evidence);
        var base = evidence.original();
        if (base.answer().isEmpty())
            return "Question " + base.id() + " : non résolue (" + base.status()
                    + "). Aucun certificat supplémentaire.";
        var basis = base.certificate().orElseThrow().basis();
        var route = evidence.optionalRoute();
        String proof = route.isEmpty() ? "routage non demandé"
                : route.orElseThrow().proofChecked()
                    ? evidence.additionalCheckedProof()
                        ? "preuve complémentaire indépendante vérifiée"
                        : "preuve optionnelle vérifiée, sans élévation de certification"
                    : "aucune preuve syntaxique supplémentaire";
        return "Question " + base.id() + " : "
                + (base.answer().orElseThrow() ? "OUI" : "NON")
                + "; support initial=" + basis + "; " + proof
                + "; prémisses externes de la preuve optionnelle="
                + evidence.routedProofDependencies().size()
                + "; sources déclaratives non vérifiées empiriquement.";
    }
}
