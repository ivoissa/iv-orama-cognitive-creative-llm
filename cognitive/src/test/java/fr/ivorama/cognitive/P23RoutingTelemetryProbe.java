package fr.ivorama.cognitive;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs offline against real FCI/ProofChecker; provider responses are synthetic. */
public final class P23RoutingTelemetryProbe {
    private static int assertions;
    private static void check(boolean condition,String label) {
        assertions++;
        if(!condition) throw new AssertionError(label);
    }
    private static SemanticExplanation hint(Formula goal,Collection<SemanticExplanation.Premise> premises) {
        return new SemanticExplanation(SemanticExplanation.Kind.IMPLICATION,
                SemanticExplanation.Status.COMPLETE,ModelExplorer.Status.PROVEN_TRUE,Optional.of(goal),
                Optional.empty(),Optional.empty(),List.of(new SemanticExplanation.Core(List.copyOf(premises),true,true)),
                true,0,0,0,"synthetic", "Untrusted simulation");
    }
    public static void main(String[] args) {
        var p=Formula.atom("p"); var q=Formula.atom("q");
        var c=new IntegratedQuestionCycle();
        c.teach(p,"human-p"); c.teach(Formula.implies(p,q),"human-implication");
        var answered=c.ask(q);
        var previousJournal=c.journal();
        var opts=new P23ProofRouting.Options(64,10_000,2,8);
        AtomicInteger calls=new AtomicInteger();
        SemanticExplanationProvider fake=new SemanticExplanationProvider(){
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> ps,Formula goal){
                calls.incrementAndGet();return hint(goal,ps);
            }
            public boolean matches(SemanticExplanation e,Collection<SemanticExplanation.Premise> ps,Formula goal){return true;}
        };
        var measurement=P23RoutingTelemetry.measure(c,answered.id(),fake,opts);
        check(measurement.receipt().isPresent(),"decided answer has receipt");
        check(measurement.hasCheckedProof(),"actual proof checker must verify");
        check(measurement.explainCalls()==1,"one provider explanation call");
        check(measurement.matchesCalls()==1,"one freshness check");
        check(calls.get()==1,"delegation");
        check(measurement.totalWallNanos()>=0,"elapsed metric");
        check(measurement.providerWallNanos()>=0,"provider metric");
        check(measurement.threadAllocatedBytes()>=-1,"allocation optional");
        check(c.journal().equals(previousJournal),"routing leaves FCI journal unchanged");
        check(c.question(answered.id()).equals(answered),"baseline report unchanged");
        check(c.isProofRoutingReceiptCurrent(answered.id(),measurement.receipt().orElseThrow()),"current receipt");
        var skipped=P23RoutingTelemetry.measure(c,answered.id(),fake,new P23ProofRouting.Options(64,10_000,0,8));
        check(skipped.explainCalls()==0 && skipped.matchesCalls()==0,"provider skip bound");
        check(skipped.hasCheckedProof(),"fallback proof checked without SAT");
        var premiseSkipped=P23RoutingTelemetry.measure(c,answered.id(),fake,new P23ProofRouting.Options(64,10_000,2,1));
        check(premiseSkipped.receipt().orElseThrow().route()==P23ProofRouting.Route.SKIPPED_PREMISE_LIMIT,"premise bound");
        check(premiseSkipped.explainCalls()==0 && premiseSkipped.matchesCalls()==0,"no provider call past premise budget");
        var fail=new SemanticExplanationProvider(){
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> ps,Formula goal){throw new IllegalStateException("planned outage");}
            public boolean matches(SemanticExplanation e,Collection<SemanticExplanation.Premise> ps,Formula goal){throw new AssertionError("unreachable");}
        };
        var fallback=P23RoutingTelemetry.measure(c,answered.id(),fail,opts);
        check(fallback.explainCalls()==1 && fallback.matchesCalls()==0,"failure counted");
        check(fallback.receipt().orElseThrow().guidance()==GuidedProofSearch.GuidanceStatus.FAILED,"failure visible");
        check(fallback.hasCheckedProof(),"verified fallback after outage");
        var unresolved=c.ask(Formula.atom("other"));
        var notRouted=P23RoutingTelemetry.measure(c,unresolved.id(),fake,opts);
        check(notRouted.receipt().isEmpty(),"undecided question not routed");
        check(notRouted.explainCalls()==0,"provider not invoked for undecided question");
        c.retract("P1");
        check(!c.isProofRoutingReceiptCurrent(answered.id(),measurement.receipt().orElseThrow()),"revision invalidates receipt");
        check(c.question(answered.id()).answer().isEmpty(),"baseline reopens after premise retraction");
        System.out.println("P23RoutingTelemetryProbe PASS: "+assertions+" assertions (simulated provider)");
    }
}
