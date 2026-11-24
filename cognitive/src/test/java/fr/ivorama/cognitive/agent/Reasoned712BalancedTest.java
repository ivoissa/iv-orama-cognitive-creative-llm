package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for explicitly selected BALANCED and direct, single-decision replay. */
class Reasoned712BalancedTest {
    @TempDir Path root;
    ReasonedObserverSession.Plan model() {
        return new ReasonedObserverSession.Plan("three", true,
            Map.of("alpha",Map.of("a",true,"b",true,"c",true),
                   "beta",Map.of("a",false,"b",false,"c",false)),
            List.of(new ReasonedObserverSession.Probe("a","a.txt",1,40,"a=yes","a=no"),
                    new ReasonedObserverSession.Probe("b","b.txt",1,40,"b=yes","b=no"),
                    new ReasonedObserverSession.Probe("c","c.txt",1,40,"c=yes","c=no")));
    }
    ReasonedObserverSession.Result run(InvestigationOptions.ProbePolicy policy) throws Exception {
        return new ReasonedObserverSession(new ReadOnlyWorkspace(root),model(),null,
                new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true,false,policy))
                .investigate("Vérifier les hypothèses");
    }
    void fixtures(String a,String b,String c)throws Exception {
        Files.writeString(root.resolve("a.txt"),a);
        Files.writeString(root.resolve("b.txt"),b);
        Files.writeString(root.resolve("c.txt"),c);
    }
    @Test void balancedFindsWitnessOnSecondPageOfSecondProbe() throws Exception {
        fixtures("neutral\n".repeat(280),"neutral\n".repeat(40)+"b=yes\n","neutral\n".repeat(280));
        assertEquals(State.UNDETERMINED,run(InvestigationOptions.ProbePolicy.COVERAGE_FIRST).finding().state());
        var improved=run(InvestigationOptions.ProbePolicy.BALANCED);
        assertEquals(State.PROVEN_CONDITIONALLY,improved.finding().state());
        assertEquals(List.of("a","a","b","b","c"),improved.steps().stream().map(ReasonedObserverSession.Step::signal).toList());
        assertEquals(5,improved.report().toolCalls());
    }
    @Test void balancedAlsoHasDocumentedLosses() throws Exception {
        fixtures("neutral\n".repeat(80)+"a=yes\n","neutral\n".repeat(280),"neutral\n".repeat(280));
        assertEquals(State.PROVEN_CONDITIONALLY,run(InvestigationOptions.ProbePolicy.COVERAGE_FIRST).finding().state());
        assertEquals(State.UNDETERMINED,run(InvestigationOptions.ProbePolicy.BALANCED).finding().state());
    }
    @Test void directVerifierChecksOriginalDecisionWithoutLlm()throws Exception {
        fixtures("neutral\n".repeat(280),"b=yes\n","neutral\n".repeat(280));
        var observed=run(InvestigationOptions.ProbePolicy.BALANCED);
        var verdict=AgentDecisionVerifier.verifyObservedDecision(model(),observed);
        assertEquals(AgentDecisionVerifier.Status.SUPPORTED_CONDITIONALLY,verdict.status());
        assertEquals(List.of("F3"),verdict.verifiedEvidenceIds());
    }
    @Test void directVerifierDetectsTampering()throws Exception {
        fixtures("neutral\n".repeat(280),"b=yes\n","neutral\n".repeat(280));
        var result=run(InvestigationOptions.ProbePolicy.BALANCED);
        var corrupted=new ReasonedObserverSession.Result(result.report(),result.finding(),
                result.steps(),List.of(),result.commentaryError(),result.completion());
        assertEquals(AgentDecisionVerifier.Status.INTEGRITY_ERROR,
                AgentDecisionVerifier.verifyObservedDecision(model(),corrupted).status());
    }
    @Test void noExperimentalPolicyEnabledByDefault() {
        assertEquals(InvestigationOptions.ProbePolicy.LEGACY,InvestigationOptions.defaults().probePolicy());
        assertEquals(InvestigationOptions.ProbePolicy.COVERAGE_FIRST,
                new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true,true).probePolicy());
    }
    @Test void actualDecisionAuditModeIsExplicit() {
        String existing=System.getProperty("ivorama.agent.decisionAuditMode");
        String legacy=System.getProperty("ivorama.agent.verifyDecisions");
        try {
            System.setProperty("ivorama.agent.decisionAuditMode","DIRECT");
            assertEquals(AgentDecisionVerifier.AuditMode.DIRECT,AgentDecisionVerifier.auditModeFromProperties());
            System.clearProperty("ivorama.agent.decisionAuditMode");
            System.setProperty("ivorama.agent.verifyDecisions","true");
            assertEquals(AgentDecisionVerifier.AuditMode.QWEN,AgentDecisionVerifier.auditModeFromProperties());
        } finally {
            if(existing==null)System.clearProperty("ivorama.agent.decisionAuditMode");
            else System.setProperty("ivorama.agent.decisionAuditMode",existing);
            if(legacy==null)System.clearProperty("ivorama.agent.verifyDecisions");
            else System.setProperty("ivorama.agent.verifyDecisions",legacy);
        }
    }
}
