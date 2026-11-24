package fr.ivorama.cognitive;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Validations du contrôleur DEV-100 : elles ne mesurent PAS la cognition. */
public final class Development100Test {
    private static int assertions;
    private static void check(boolean value,String reason) {
        assertions++;
        if(!value)throw new AssertionError(reason);
    }
    private static void reject(Runnable runnable,String reason) {
        boolean rejected=false;
        try {runnable.run();} catch(IllegalArgumentException x) {rejected=true;}
        check(rejected,reason);
    }
    public static int runAll() throws Exception {
        var original=Path.of("evaluation/dev100/SCENARIOS_100_DEVELOPPEMENT.tsv");
        var base=Development100.evaluate(original);
        check(base.cases().size()==100,"Exactly 100 cases");
        check(base.passed()+base.failed()+base.gaps()==100,"No forgotten case");
        check(base.runnable()==base.passed()+base.failed(),"Runnable count complete");
        check(base.gaps()>0,"Unrepresented capacities are retained");
        check(base.failed()==0 && base.passed()==75 && base.gaps()==25,
            "Regression reference: 75 PASS, 0 FAIL, 25 GAP after relational quantifier");
        check(base.evaluator().total()==base.runnable(),"No use of sealed external API");
        check(base.evaluator().complete()==base.passed(),"Scoring independent of labels");
        check(base.evaluator().semanticNotAssessed()>0,"Missing oracle is visible");
        check(base.cases().stream().map(Development100.Case::id).distinct().count()==100,"Unique IDs");
        check(base.cases().stream().filter(c->c.scope().equals("NON_PRIS_EN_CHARGE")).count()==base.gaps(),
            "Unsupported counted with its own status");
        check(base.cases().stream().filter(c->c.scope().equals("FAIL")).count()==base.failed(),
            "Failures counted");
        var duplicate=Files.createTempFile("ivoc-dev100-dup", ".tsv");
        var modified=Files.createTempFile("ivoc-dev100-mutant", ".tsv");
        try {
            String body=Files.readString(original,StandardCharsets.UTF_8);
            var rows=body.split("\n",-1);
            String[] f=rows[2].split("\t",-1);
            f[0]="DED-01";
            rows[2]=String.join("\t",f);
            Files.writeString(duplicate,String.join("\n",rows),StandardCharsets.UTF_8);
            boolean duplicateRejected=false;
            try { Development100.evaluate(duplicate); }
            catch(IllegalArgumentException ex){duplicateRejected=true;}
            check(duplicateRejected,"Duplicate IDs refused");
            // Mutating a reference answer should cause regression, not a redefinition of truth.
            var changed=body.replaceFirst("(?m)^(DED-01\\t[^\\n]*)", "$1");
            String[] other=body.split("\n",-1);
            String[] first=other[1].split("\t",-1);
            check(first[0].equals("DED-01"),"Fixed test position");
            first[8]="NO"; // Expected initial answer (wrong on purpose).
            other[1]=String.join("\t",first);
            Files.writeString(modified,String.join("\n",other),StandardCharsets.UTF_8);
            var mutant=Development100.evaluate(modified);
            check(mutant.failed()>base.failed() && mutant.passed()<base.passed(),
                "Wrong oracle must lower score and increase failures");
            check(mutant.cases().stream().anyMatch(c->c.id().equals("DED-01")&&c.scope().equals("FAIL")),
                "Oracle mutant detected on the expected case");
        } finally {Files.deleteIfExists(duplicate);Files.deleteIfExists(modified);}
        var repeated=Development100.evaluate(original);
        check(base.passed()==repeated.passed() && base.failed()==repeated.failed() &&
              base.gaps()==repeated.gaps() && base.sourceHash().equals(repeated.sourceHash()),
              "Deterministic rerun");
        return assertions;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("DEVELOPMENT100-TEST OK - "+runAll()+" assertions");
    }
}
