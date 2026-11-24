package fr.ivorama.cognitive;

import java.nio.file.*;
import java.util.*;

/** A/B simulation. Baseline is identity-pass-through; audit does not change the answer. */
public final class P31ModelAgnosticFixtureBench {
    private P31ModelAgnosticFixtureBench() {}
    private record Case(String id, String model, String facts, String draft, P31CandidateAnswerAudit.Status expected) {}
    private static long percentile(long[] values, int p) {
        long[] sorted = values.clone(); Arrays.sort(sorted);
        return sorted[Math.min(sorted.length-1, Math.max(0,(int)Math.ceil(sorted.length*p/100.0)-1))];
    }
    public static void main(String[] ignored) throws Exception {
        var lines = Files.readAllLines(Path.of("evaluation/p31/model-agnostic-fixtures.tsv"));
        List<Case> cases = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split("\t", -1);
            if (c.length != 5) throw new AssertionError("Unexpected TSV schema");
            cases.add(new Case(c[0],c[1],c[2],c[3],P31CandidateAnswerAudit.Status.valueOf(c[4])));
        }
        int passed = 0, contrasted = 0;
        for (Case c : cases) {
            // Without IV-ORAMA: original model answer is returned without any logical decision.
            String passthrough = c.draft;
            var reviewed = P31CandidateAnswerAudit.inspect(c.model,c.facts,passthrough,ModelExplorer.Budget.small());
            if (reviewed.status() != c.expected) throw new AssertionError(c.id+": "+reviewed.status()+" vs "+c.expected);
            if (!reviewed.draft().equals(passthrough)) throw new AssertionError("LLM output must be preserved");
            if (reviewed.status() == P31CandidateAnswerAudit.Status.CONTRADICTED) contrasted++;
            passed++;
        }
        for (int n=0;n<30;n++)for (Case c : cases)
            P31CandidateAnswerAudit.inspect(c.model,c.facts,c.draft,ModelExplorer.Budget.small());
        int iterations = 24;
        long[] measures = new long[cases.size()*iterations];
        int k=0;
        for (int round=0;round<iterations;round++) for(Case c:cases) {
            long start=System.nanoTime();
            var r=P31CandidateAnswerAudit.inspect(c.model,c.facts,c.draft,ModelExplorer.Budget.small());
            measures[k++] = System.nanoTime()-start;
            if(r.status()!=c.expected)throw new AssertionError("Non-deterministic decision");
        }
        System.out.printf(Locale.ROOT, "P3.1,SIMULATION_ONLY,passed=%d/%d,detected_conflicts=%d,pass_through_text_unchanged=true,p50_us=%.2f,p95_us=%.2f%n",
                passed,cases.size(),contrasted,percentile(measures,50)/1000.0,percentile(measures,95)/1000.0);
    }
}
