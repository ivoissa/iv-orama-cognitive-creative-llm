package fr.ivorama.cognitive;

import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/**
 * P3.1 experimental, provider-neutral audit of DECLARATIVE French fragments.
 * Input is an untrusted model draft. No model invocation, implicit facts,
 * linguistic guesswork, or mutation of an FCI question occurs here.
 * A consistent assertion is NOT automatically a supported assertion.
 * Any untranslated span prevents an overall verified verdict.
 */
public final class P31CandidateAnswerAudit {
    private P31CandidateAnswerAudit() { }

    public enum Status {
        ENTAILED, CONTRADICTED, CONSISTENT_UNSUPPORTED,
        UNRESOLVED_LANGUAGE, INCONSISTENT_REFERENCE, RESOURCE_LIMIT
    }
    public record Claim(String excerpt, Status status, String premiseId) {
        public Claim {
            Objects.requireNonNull(excerpt); Objects.requireNonNull(status);
            Objects.requireNonNull(premiseId);
        }
    }
    public record Review(String providerLabel, String reference, String draft, Status status,
                         List<Claim> claims, List<String> unresolved, String limitation) {
        public Review {
            Objects.requireNonNull(providerLabel); Objects.requireNonNull(reference);
            Objects.requireNonNull(draft); Objects.requireNonNull(status);
            claims = List.copyOf(claims);
            unresolved = List.copyOf(unresolved);
            Objects.requireNonNull(limitation);
        }
        public boolean isFullySupported() { return status == Status.ENTAILED; }
    }

    /** No vendor-specific types or credentials; caller may adapt any GenerationBackend. */
    public static Review inspect(String providerLabel, String reference, String draft,
                                 ModelExplorer.Budget budget) {
        Objects.requireNonNull(providerLabel); Objects.requireNonNull(reference);
        Objects.requireNonNull(draft); Objects.requireNonNull(budget);
        if (providerLabel.isBlank() || providerLabel.length() > 128)
            throw new IllegalArgumentException("Provider label out of bounds");
        if (reference.isBlank() || draft.isBlank() || reference.length() + draft.length() > 6000)
            return unresolved(providerLabel, reference, draft, "Empty text or total text budget exceeded");
        String joined = reference + "\n" + draft;
        int draftStart = reference.length() + 1;
        var document = new FrenchStructuredTranslator().analyzeText(joined);
        var problems = new ArrayList<String>(document.unresolved());
        // P3.4: untranslated REFERENCE invalidates all comparisons. If only a
        // draft span is untranslated, parsed claims may be diagnosed individually,
        // but the whole answer remains UNRESOLVED_LANGUAGE and uncertified.
        var scanIssues = DiscourseSegments.scan(joined).unresolved();
        boolean referenceComplete = scanIssues.isEmpty();
        boolean draftIncomplete = false;
        for (var segment : document.segments()) {
            if (!segment.interpreted()) {
                problems.add("Untranslated passage: " + segment.start() + "-" + segment.end());
                if (segment.end() <= reference.length()) referenceComplete = false;
                else if (segment.start() >= draftStart) draftIncomplete = true;
                else referenceComplete = false;
            }
        }
        if (!referenceComplete) return new Review(providerLabel, reference, draft,
                Status.UNRESOLVED_LANGUAGE, List.of(), problems,
                "Untranslated or ambiguous reference: no derived claim may be certified");
        var base = new ArrayList<Premise>();
        var candidate = new ArrayList<Premise>();
        for (Premise p : document.propositions()) {
            String span = p.evidence().source(); // trusted parser-generated offset, not user-supplied metadata
            String[] parts = span.split(":");
            int start = Integer.parseInt(parts[1]);
            int end = Integer.parseInt(parts[2]);
            if (end <= reference.length()) base.add(p);
            else if (start >= draftStart) candidate.add(p);
            else problems.add("A proposition crosses the reference/draft boundary");
        }
        if ((!problems.isEmpty() && !draftIncomplete) || base.isEmpty() || candidate.isEmpty())
            return new Review(providerLabel, reference, draft, Status.UNRESOLVED_LANGUAGE,
                    List.of(), problems.isEmpty() ? List.of("No independently translated premises and claims") : problems,
                    "No conclusion without explicit and complete translations");
        try {
            var all = new ArrayList<Premise>(base);
            all.addAll(candidate);
            var compiled = StructuredLogicCompiler.compile(new Problem(joined, document.entities(),
                    document.predicates(), all, candidate.get(0).expression(), List.of(), List.of()));
            var baseFormulas = new ArrayList<Formula>();
            var allFormulas = new ArrayList<Formula>();
            for (Premise p : base) baseFormulas.add(compiled.premiseIds().get(p.id()));
            for (Premise p : all) allFormulas.add(compiled.premiseIds().get(p.id()));
            var refConsistency = ModelExplorer.analyze(baseFormulas, Formula.Bottom.INSTANCE, budget);
            if (refConsistency.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                return resource(providerLabel, reference, draft);
            if (refConsistency.status() == ModelExplorer.Status.INCONSISTENT)
                return new Review(providerLabel, reference, draft, Status.INCONSISTENT_REFERENCE,
                        List.of(), List.of(), "The reference itself is inconsistent; do not infer claims by explosion");
            var combined = ModelExplorer.analyze(allFormulas, Formula.Bottom.INSTANCE, budget);
            if (combined.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                return resource(providerLabel, reference, draft);
            var claims = new ArrayList<Claim>();
            boolean allSupported = true, anyContradicted = false;
            for (Premise p : candidate) {
                Formula formula = compiled.premiseIds().get(p.id());
                var semantic = ModelExplorer.analyze(baseFormulas, formula, budget);
                if (semantic.status() == ModelExplorer.Status.RESOURCE_LIMIT)
                    return resource(providerLabel, reference, draft);
                Status status = switch (semantic.status()) {
                    case PROVEN_TRUE -> Status.ENTAILED;
                    case PROVEN_FALSE -> Status.CONTRADICTED;
                    case UNDETERMINED -> Status.CONSISTENT_UNSUPPORTED;
                    case INCONSISTENT -> Status.INCONSISTENT_REFERENCE;
                    case RESOURCE_LIMIT -> Status.RESOURCE_LIMIT;
                };
                if (status != Status.ENTAILED) allSupported = false;
                if (status == Status.CONTRADICTED) anyContradicted = true;
                claims.add(new Claim(p.evidence().excerpt(), status, p.id()));
            }
            Status overall = draftIncomplete ? Status.UNRESOLVED_LANGUAGE
                    : (combined.status() == ModelExplorer.Status.INCONSISTENT || anyContradicted)
                        ? Status.CONTRADICTED
                        : allSupported ? Status.ENTAILED : Status.CONSISTENT_UNSUPPORTED;
            return new Review(providerLabel, reference, draft, overall, claims,
                    draftIncomplete ? problems : List.of(),
                    draftIncomplete ? "Partial claim diagnostics only: at least one draft span is untranslated; "
                         + "no whole-answer certification and no assertion of empirical truth."
                         : "Only the parsed, finite, controlled French fragment was checked. "
                         + "Compatibility does not establish empirical truth; no LLM quality claim.");
        } catch (StructuredLogicCompiler.Limit ex) {
            return resource(providerLabel, reference, draft);
        } catch (IllegalArgumentException ex) {
            return new Review(providerLabel, reference, draft, Status.UNRESOLVED_LANGUAGE,
                    List.of(), List.of("Compilation rejected: " + ex.getMessage()),
                    "No guessed semantic interpretation");
        }
    }

    private static Review unresolved(String model, String reference, String draft, String reason) {
        return new Review(model, reference, draft, Status.UNRESOLVED_LANGUAGE,
                List.of(), List.of(reason), "No partial semantic certification");
    }
    private static Review resource(String model, String reference, String draft) {
        return new Review(model, reference, draft, Status.RESOURCE_LIMIT,
                List.of(), List.of(), "Calculation limit; no partial semantic certification");
    }
}
