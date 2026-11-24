package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReasonedObserver076Test {
    @TempDir Path root;
    static ReasonedObserverSession.Plan plan(boolean exhaustive) {
        return new ReasonedObserverSession.Plan("test", exhaustive,
                Map.of("panne_a", Map.of("signal", true), "panne_b", Map.of("signal", false)),
                List.of(new ReasonedObserverSession.Probe("signal", "measurement.txt", 1, 40, "probe=oui", "probe=non")));
    }
    ReasonedObserverSession.Result run(String text, ReasonedObserverSession.Commentary commentary) throws Exception {
        Files.writeString(root.resolve("measurement.txt"), text);
        return new ReasonedObserverSession(new ReadOnlyWorkspace(root), plan(true), commentary).investigate("Quelle hypothèse ?");
    }
    @Test void threeCasesWithoutQwen() throws Exception {
        var yes = run("probe=oui\n", null);
        assertEquals(State.PROVEN_CONDITIONALLY, yes.finding().state());
        assertEquals("panne_a", yes.finding().cause());
        assertEquals(List.of("panne_b"), yes.finding().refuted());
        assertEquals("signal", yes.steps().getFirst().signal());
        assertEquals(1, yes.report().toolCalls());
        assertTrue(yes.facts().getFirst().source().contains("[F1]"));
        var unknown = run("// probe=oui\nabsence de mesure\n", null);
        assertEquals(State.UNDETERMINED, unknown.finding().state());
        assertTrue(unknown.facts().isEmpty());
        assertEquals("UNKNOWN", unknown.steps().getFirst().result());
        assertEquals(1, unknown.steps().size());
        var conflict = run("probe=oui\nprobe=non\n", null);
        assertEquals(State.INCONSISTENT, conflict.finding().state());
        assertEquals("", conflict.finding().cause());
        assertEquals(2, conflict.facts().size());
    }
    @Test void commentaryCannotChangePremisesOrConclusionAndFailurePreservesReceipt() throws Exception {
        var calls = new AtomicInteger();
        for (String text : List.of("probe=oui\n", "aucune mesure\n", "probe=oui\nprobe=non\n")) {
            var offline = run(text, null);
            var online = run(text, (q, state, evidence) -> { calls.incrementAndGet(); return "La réponse est arbitrairement panne_b [F99]"; });
            assertEquals(offline.finding(), online.finding());
            assertEquals(offline.steps(), online.steps());
            assertTrue(online.report().answer().contains("COMMENTAIRE QWEN NON CERTIFIÉ"));
            assertEquals(Set.of("F99"), online.report().unsupportedCitations());
        }
        assertEquals(3, calls.get());
        var failed = run("probe=oui\n", (q, state, evidence) -> { throw new IllegalStateException("indisponible"); });
        assertEquals(State.PROVEN_CONDITIONALLY, failed.finding().state());
        assertTrue(failed.commentaryError().contains("indisponible"));
    }
    @Test void nonExhaustiveModelNeverTurnsEliminationIntoProof() throws Exception {
        Files.writeString(root.resolve("measurement.txt"), "probe=oui\n");
        var result = new ReasonedObserverSession(new ReadOnlyWorkspace(root), plan(false), null).investigate("Cause ?");
        assertEquals(State.UNDETERMINED, result.finding().state());
        assertEquals("", result.finding().cause());
    }
    @Test void truncationDoesNotCreateNegativeEvidence() throws Exception {
        String text = "x".repeat(1000) + "\n".repeat(2) + "probe=non\n";
        var result = run(text, null);
        assertEquals(State.PROVEN_CONDITIONALLY, result.finding().state());
        assertEquals("panne_b", result.finding().cause());
        // Window stops before the negative witness: absence must remain unknown.
        Files.writeString(root.resolve("measurement.txt"), "absent\n".repeat(41) + "probe=non\n");
        result = new ReasonedObserverSession(new ReadOnlyWorkspace(root), plan(true), null,
                new InvestigationOptions(InvestigationOptions.Goal.DIAGNOSE,true,false)).investigate("Cause ?");
        assertEquals(State.UNDETERMINED, result.finding().state());
        assertTrue(result.facts().isEmpty());
        assertTrue(result.report().evidence().getFirst().truncated());
    }
    @Test void minimaxReallySelectsTheBalancedProbeAndThenNarrowsAgain() throws Exception {
        Files.writeString(root.resolve("balanced.txt"), "test=oui\n");
        Files.writeString(root.resolve("rare.txt"), "test=non\n");
        Map<String, Map<String,Boolean>> hypotheses = new LinkedHashMap<>();
        for(int i=1;i<=4;i++) hypotheses.put("h"+i, Map.of("rare",i==1,"balanced",i<=2));
        var probes = List.of(new ReasonedObserverSession.Probe("rare","rare.txt",1,40,"test=oui","test=non"),
                new ReasonedObserverSession.Probe("balanced","balanced.txt",1,40,"test=oui","test=non"));
        var result = new ReasonedObserverSession(new ReadOnlyWorkspace(root),
                new ReasonedObserverSession.Plan("minimax",true,hypotheses,probes),null).investigate("Cause ?");
        assertEquals(List.of("balanced","rare"), result.steps().stream().map(ReasonedObserverSession.Step::signal).toList());
        assertEquals("h2",result.finding().cause());
    }
    @Test void characterBudgetCannotManufactureAWitnessByCuttingItsSuffix() throws Exception {
        String padding = "x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(758)+"\n";
        Files.writeString(root.resolve("a.txt"),padding); Files.writeString(root.resolve("b.txt"),padding);
        Files.writeString(root.resolve("c.txt"),"probe=ouiEXTRA\n");
        assertEquals(2991,new ReadOnlyWorkspace(root).read("a.txt",1,40).content().length());
        var probes=List.of(new ReasonedObserverSession.Probe("s1","a.txt",1,40,"probe=oui","probe=non"),
                new ReasonedObserverSession.Probe("s2","b.txt",1,40,"probe=oui","probe=non"),
                new ReasonedObserverSession.Probe("s3","c.txt",1,40,"probe=oui","probe=non"));
        var plan=new ReasonedObserverSession.Plan("clipping",true,Map.of("a",Map.of("s1",true,"s2",true,"s3",true),
                "b",Map.of("s1",false,"s2",false,"s3",false)),probes);
        var result=new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan,null,
                new InvestigationOptions(InvestigationOptions.Goal.DIAGNOSE,true,false)).investigate("Cause ?");
        assertTrue(result.report().evidence().getLast().content().endsWith("probe=oui"));
        assertTrue(result.facts().isEmpty()); assertEquals(State.UNDETERMINED,result.finding().state());
        assertTrue(result.report().evidenceLimitReached());
    }
    @Test void malformedModelsAndEscapingPathsAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new ReasonedObserverSession.Plan("bad",true,
                Map.of("a",Map.of("unbound",true),"b",Map.of()), plan(true).probes()));
        assertThrows(IllegalArgumentException.class, () -> new ReasonedObserverSession.Probe("s","f",1,40,"same","same"));
        var malicious = new ReasonedObserverSession.Plan("escape",true, plan(true).hypotheses(),
                List.of(new ReasonedObserverSession.Probe("signal","../outside.txt",1,40,"oui","non")));
        assertThrows(SecurityException.class, () -> new ReasonedObserverSession(new ReadOnlyWorkspace(root),malicious,null).investigate("Cause ?"));
        var file = root.resolve("plan.json");
        Files.writeString(file,"{\"name\":\"x\",\"name\":\"y\"}");
        assertThrows(java.io.IOException.class, () -> ReasonedObserverSession.Plan.load(file));
    }
}
