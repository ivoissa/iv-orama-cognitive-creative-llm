package fr.ivorama.cognitive;

import java.util.*;

/** Deterministic, vendor-neutral simulation against the actual linguistic and logical core. */
public final class P31CandidateAnswerAuditProbe {
    private static int assertions;
    private static void check(boolean x, String message) {
        assertions++;
        if (!x) throw new AssertionError("P3.1: " + message);
    }
    private static P31CandidateAnswerAudit.Review audit(String model, String facts, String output) {
        return P31CandidateAnswerAudit.inspect(model, facts, output, ModelExplorer.Budget.small());
    }
    public static void main(String[] ignored) {
        assertions = 0;
        String facts = "La porte A est ouverte. Le document B est valide.";
        var q = audit("fake-qwen", facts, "La porte A est ouverte.");
        check(q.status() == P31CandidateAnswerAudit.Status.ENTAILED, "entailed declaration");
        check(q.isFullySupported(), "fully supported");
        check(q.claims().size() == 1, "exactly one interpreted claim");
        for (String model : List.of("mock-openai", "mock-anthropic", "mock-ollama", "local-fixture")) {
            var alternative = audit(model, facts, "La porte A est ouverte.");
            check(alternative.status() == q.status(), "vendor-neutral verdict " + model);
            check(alternative.claims().equals(q.claims()), "vendor-neutral claims " + model);
        }
        check(audit("fake-llm", facts, "La porte A n'est pas ouverte.").status()
                == P31CandidateAnswerAudit.Status.CONTRADICTED, "contradicted declaration");
        check(audit("fake-llm", facts, "La porte A est fermée.").status()
                == P31CandidateAnswerAudit.Status.CONSISTENT_UNSUPPORTED,
                "missing antonym not invented");
        check(audit("fake-llm", facts, "La porte A est ouverte. La porte A n'est pas ouverte.").status()
                == P31CandidateAnswerAudit.Status.CONTRADICTED, "internal draft contradiction");
        check(audit("fake-llm", "La porte A est ouverte. La porte A n'est pas ouverte.",
                "La porte A est ouverte.").status()
                == P31CandidateAnswerAudit.Status.INCONSISTENT_REFERENCE,
                "inconsistent reference rejects explosion");
        check(audit("fake-llm", facts, "C'est probablement une excellente idée.").status()
                == P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,
                "do not infer unparsed natural language");
        check(audit("fake-llm", facts, "La porte A est ouverte. Cela semble logique.").status()
                == P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,
                "no certification when one passage is unparsed");
        check(audit("fake-llm", facts, "« La porte A n'est pas ouverte ».").status()
                == P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,
                "quoted statement must not be treated as a fact");
        check(audit("fake-llm", facts, "La porte A est ouverte.").claims().get(0).premiseId().startsWith("txt_p"),
                "source-aligned premise identifiers");
        check(!audit("fake-llm", facts, "La porte A est fermée.").isFullySupported(),
                "mere consistency is not justification");
        check(P31CandidateAnswerAudit.inspect("fixture", facts, "La porte A est ouverte.",
                new ModelExplorer.Budget(0, 1)).status() == P31CandidateAnswerAudit.Status.RESOURCE_LIMIT,
                "honor restricted model budget");
        System.out.println("P31CandidateAnswerAuditProbe PASS: " + assertions + " assertions");
    }
}
