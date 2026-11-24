package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in coverage-first audit; the 0.7.8 behaviour remains the default. */
class Reasoned079CoverageTest {
    @TempDir Path root;

    private ReasonedObserverSession.Plan model() {
        return new ReasonedObserverSession.Plan("coverage",true,
                Map.of("alpha",Map.of("a",true,"b",true),"beta",Map.of("a",false,"b",false)),
                List.of(new ReasonedObserverSession.Probe("a","a.txt",1,40,"a=yes","a=no"),
                        new ReasonedObserverSession.Probe("b","b.txt",1,40,"b=yes","b=no")));
    }
    private ReasonedObserverSession.Result run(InvestigationOptions.Goal goal,boolean enabled) throws Exception {
        return new ReasonedObserverSession(new ReadOnlyWorkspace(root),model(),null,
                new InvestigationOptions(goal,true,true,enabled)).investigate("Audit des deux sondes ?");
    }
    @Test void prioritisesNewProbeBeforeExtraPagesInExplicitAudit() throws Exception {
        Files.writeString(root.resolve("a.txt"),"neutral\n".repeat(280));
        Files.writeString(root.resolve("b.txt"),"b=yes\n");
        var legacy=run(InvestigationOptions.Goal.AUDIT_ALL,false);
        var selective=run(InvestigationOptions.Goal.AUDIT_ALL,true);
        assertEquals(State.UNDETERMINED,legacy.finding().state());
        assertEquals(List.of("a","a","a","a","a"),legacy.steps().stream().map(ReasonedObserverSession.Step::signal).toList());
        assertEquals(State.PROVEN_CONDITIONALLY,selective.finding().state());
        assertEquals("alpha",selective.finding().cause());
        assertEquals(List.of("a","b","a","a","a"),selective.steps().stream().map(ReasonedObserverSession.Step::signal).toList());
        assertFalse(selective.completion().complete());
        assertTrue(selective.completion().unreadSignals().contains("a"));
        assertEquals(5,selective.report().toolCalls());
    }
    @Test void diagnosisDoesNotSwitchToCoverageFirst() throws Exception {
        Files.writeString(root.resolve("a.txt"),"neutral\n".repeat(280));
        Files.writeString(root.resolve("b.txt"),"b=yes\n");
        var original=run(InvestigationOptions.Goal.DIAGNOSE,false);
        var optIn=run(InvestigationOptions.Goal.DIAGNOSE,true);
        assertEquals(original.finding().state(),optIn.finding().state());
        assertEquals(original.steps(),optIn.steps());
    }
    @Test void originalConstructorAndSystemDefaultRemainOff() {
        assertFalse(new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true).coverageFirst());
        assertFalse(InvestigationOptions.defaults().coverageFirst());
    }
}
