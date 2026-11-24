package fr.ivorama.cognitive;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone, dependency-free integration probe; Maven runs it through P23RoutingIntegrationTest. */
public final class P23RoutingProbe {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError("P2.3 " + message);
    }
    private static Formula a(String name) { return Formula.atom(name); }
    private static SemanticExplanation candidate(Formula goal,List<SemanticExplanation.Premise> subset) {
        return new SemanticExplanation(SemanticExplanation.Kind.IMPLICATION,
                SemanticExplanation.Status.COMPLETE, ModelExplorer.Status.PROVEN_TRUE, Optional.of(goal),
                Optional.empty(),Optional.empty(),
                List.of(new SemanticExplanation.Core(subset,true,true)),true,
                0,0,0,"TEST-ONLY-DIGEST","Synthetic hint, not a trusted certificate");
    }
    private static SemanticExplanationProvider selected(String... ids) {
        return new SemanticExplanationProvider() {
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> premises, Formula goal) {
                Set<String> wanted=Set.of(ids);
                return candidate(goal,premises.stream().filter(p->wanted.contains(p.id())).toList());
            }
            public boolean matches(SemanticExplanation r,Collection<SemanticExplanation.Premise> p,Formula q) {
                return true; // intentionally untrusted simulated provider; still requires ProofChecker
            }
        };
    }
    private static SemanticExplanationProvider failing(AtomicInteger calls) {
        return new SemanticExplanationProvider() {
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> p,Formula q) {
                calls.incrementAndGet();throw new IllegalStateException("Simulated LLM/SAT outage");
            }
            public boolean matches(SemanticExplanation e, Collection<SemanticExplanation.Premise> p,Formula q) {
                throw new AssertionError("Must never call matches after failed explain");
            }
        };
    }
    public static void run() {
        checks=0;
        var options = new P23ProofRouting.Options(3,10_000,2,64);
        var c = new IntegratedQuestionCycle();
        for(int i=0;i<5;i++) c.teach(Formula.implies(a("u"+i),a("u"+i)),"noise-source-"+i);
        c.teach(a("p"),"assertion-source");
        c.teach(Formula.implies(a("p"),a("q")),"rule-source");
        var base = c.ask(a("q"));
        check(base.answer().equals(Optional.of(true)),"baseline decision is yes");
        check(base.certificate().isPresent(),"baseline has certificate");
        var journalBefore=c.journal();
        var receipt = c.proofRoutingReceipt(base.id(),selected("P6","P7"),options).orElseThrow();
        check(receipt.route()==P23ProofRouting.Route.GUIDED,"selects opt-in guided proof");
        check(receipt.proofChecked(),"checks guided result independently");
        check(receipt.proofDependencies().equals(Set.of(a("p"),Formula.implies(a("p"),a("q")))),"exact dependencies");
        check(Set.copyOf(receipt.selectedSupportIds()).equals(Set.of("P6","P7")),"source ids preserved");
        check(receipt.proofOperations()<=options.maxSearchOperations(),"global search operations bounded");
        check(!receipt.fallbackUsed(),"no fallback needed for valid hint");
        check(c.isProofRoutingReceiptCurrent(base.id(),receipt),"receipt current");
        check(c.journal().equals(journalBefore),"does not add interrogative acts or mutate journal");
        check(c.question(base.id()).equals(base),"baseline report unchanged");

        var degraded=new P23ProofRouting.Options(3,1,2,64);
        var limited=c.proofRoutingReceipt(base.id(),selected("P6","P7"),degraded).orElseThrow();
        check(limited.searchStatus()==ProofSearch.SearchStatus.RESOURCE_LIMIT,"resource limit explicit");
        check(limited.proof().isEmpty(),"no proof on exhaustion");
        check(c.question(base.id()).answer().equals(Optional.of(true)),"baseline preserved on exhaustion");

        var failedCalls=new AtomicInteger();
        var fallback=c.proofRoutingReceipt(base.id(),failing(failedCalls),
                new P23ProofRouting.Options(32,10_000,2,64)).orElseThrow();
        check(failedCalls.get()==1,"simulated outage invoked once");
        check(fallback.guidance()==GuidedProofSearch.GuidanceStatus.FAILED,"failure is visible");
        check(fallback.fallbackUsed(),"fallback attempted");
        check(fallback.proofChecked(),"fallback proof checked");

        var disabledCalls=new AtomicInteger();
        var disabled=c.proofRoutingReceipt(base.id(),failing(disabledCalls),
                new P23ProofRouting.Options(32,10_000,0,64)).orElseThrow();
        check(disabledCalls.get()==0,"disabled provider not invoked");
        check(disabled.proofChecked(),"indexed proof still available");

        var shortCap=c.proofRoutingReceipt(base.id(),failing(new AtomicInteger()),
                new P23ProofRouting.Options(32,10_000,2,1)).orElseThrow();
        check(shortCap.route()==P23ProofRouting.Route.SKIPPED_PREMISE_LIMIT,"premise cap skips route");
        check(shortCap.proof().isEmpty(),"no unverified proof on skipped route");

        var forged=c.proofRoutingReceipt(base.id(),selected("P1"),
                new P23ProofRouting.Options(3,10_000,1,64)).orElseThrow();
        check(forged.guidance()!=GuidedProofSearch.GuidanceStatus.USED,"unsupported hint not trusted");
        check(forged.proof().isEmpty(),"capacity-bound fallback cannot invent proof");

        c.retract("P6");
        check(!c.isProofRoutingReceiptCurrent(base.id(),receipt),"retraction invalidates receipt");
        check(c.question(base.id()).answer().isEmpty(),"FCI reopens after retraction");
        check(c.proofRoutingReceipt(base.id(),selected("P7"),options).isEmpty(),"unanswered question has no proof receipt");
        c.teach(a("p"),"replacement-source");
        var refreshed=c.question(base.id());
        check(refreshed.answer().equals(Optional.of(true)),"FCI restores with new premise");
        var replacementReceipt=c.proofRoutingReceipt(base.id(),selected("P7","P8"),options).orElseThrow();
        check(replacementReceipt.proofChecked(),"new support builds checked proof");
        check(!receipt.isCurrent(c.knowledgeVersion(),replacementReceipt.contextSnapshot(),a("q")),"old receipt invalidated by version");

        var unknown=new IntegratedQuestionCycle();
        var unresolved=unknown.ask(a("missing"));
        check(unresolved.answer().isEmpty(),"unknown goal remains open");
        check(unknown.proofRoutingReceipt(unresolved.id(),selected("X"),options).isEmpty(),"no route for open gap");

        var contradictory=new IntegratedQuestionCycle();
        contradictory.teach(a("x"),"source");
        contradictory.teach(Formula.not(a("x")),"source");
        var inc=contradictory.ask(a("x"));
        check(inc.status()==IntegratedQuestionCycle.Status.INCONSISTENT,"inconsistent premise status");
        check(contradictory.proofRoutingReceipt(inc.id(),selected("P1"),options).isEmpty(),"no route under inconsistency");

        var inverted=new IntegratedQuestionCycle();
        inverted.teach(Formula.not(a("p")),"source-not-p");
        var negative=inverted.ask(a("p"));
        check(negative.answer().equals(Optional.of(false)),"negative decision preserved");
        var notP=inverted.proofRoutingReceipt(negative.id(),selected("P1"),options).orElseThrow();
        check(notP.goal().equals(Formula.not(a("p"))),"route correct negated goal");
        check(notP.proofChecked(),"negative proof checked");
        check(!notP.isAdditionalCheckedProof(),"direct assumption does not become independent evidence");

        var invalidVersion = new ProvenanceAudit.Certificate(base.certificate().orElseThrow().goal(),
                base.certificate().orElseThrow().basis(),base.certificate().orElseThrow().proofChecked(),
                base.certificate().orElseThrow().formalProof(),base.certificate().orElseThrow().dependencies(),
                999L,base.certificate().orElseThrow().limitation(),base.certificate().orElseThrow().alternativeSupports(),
                base.certificate().orElseThrow().semanticSupportLineage());
        boolean staleRejected=false;
        try { P23ProofRouting.inspect(selected("P6","P7"),receipt.contextSnapshot(),a("q"),
                receipt.knowledgeVersion(),invalidVersion,options); }
        catch(IllegalArgumentException expected) { staleRejected=true; }
        check(staleRejected,"mismatched baseline version rejected");
        boolean unknownRejected=false;
        try { c.proofRoutingReceipt("Q-unregistered",selected("P7","P8"),options); }
        catch(IllegalArgumentException expected) { unknownRejected=true; }
        check(unknownRejected,"unknown session rejected");
        boolean invalidOptions=false;
        try {new P23ProofRouting.Options(0,100,2,64);}
        catch(IllegalArgumentException expected){invalidOptions=true;}
        check(invalidOptions,"invalid search budget refused");

        var forgedSource = new SemanticExplanationProvider() {
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> ps,Formula goal) {
                return candidate(goal,List.of(new SemanticExplanation.Premise("P8",goal,"fabricated-source","")));
            }
            public boolean matches(SemanticExplanation e,Collection<SemanticExplanation.Premise> ps,Formula goal) {
                return true;
            }
        };
        var badHint=c.proofRoutingReceipt(base.id(),forgedSource,
                new P23ProofRouting.Options(3,10000,1,64)).orElseThrow();
        check(badHint.guidance()==GuidedProofSearch.GuidanceStatus.INVALID,"forged formula-source pair rejected");
        check(badHint.route()!=P23ProofRouting.Route.GUIDED,"forged hint not used");

        c.replace("P8", "p", "changed-attribution");
        check(!c.isProofRoutingReceiptCurrent(base.id(),replacementReceipt),"source change invalidates receipt");
        check(c.question(base.id()).answer().equals(Optional.of(true)),"source-only replacement preserves truth");

        System.out.println("P23RoutingProbe PASS: " + checks + " independently checked assertions");
    }
    public static void main(String[] args){run();}
}
