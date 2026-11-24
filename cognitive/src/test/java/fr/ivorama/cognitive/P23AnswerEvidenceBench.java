package fr.ivorama.cognitive;

import java.util.*;

/**
 * Three-process comparative overhead microbenchmark: SAME baseline and supplier,
 * comparing an optional routing receipt versus the extra evidence projection.
 * This is NOT a real LogicNG or LLM benchmark. No external library involved.
 */
public final class P23AnswerEvidenceBench {
    private P23AnswerEvidenceBench() {}
    private static Formula a(String name) { return Formula.atom(name); }
    private static final class FaultyProvider implements SemanticExplanationProvider {
        @Override public SemanticExplanation explain(Collection<SemanticExplanation.Premise> premises, Formula goal) {
            throw new IllegalStateException("synthetic unavailable provider");
        }
        @Override public boolean matches(SemanticExplanation e,
                Collection<SemanticExplanation.Premise> premises, Formula goal) {
            throw new AssertionError("Unexpected");
        }
    }
    private static long percentile(long[] values, double q) {
        long[] copy = values.clone(); Arrays.sort(copy);
        return copy[Math.max(0,Math.min(copy.length-1,(int)Math.ceil(q*copy.length)-1))];
    }
    public static void main(String[] args) {
        var cycle = new IntegratedQuestionCycle();
        cycle.teach(a("a"),"S1");
        cycle.teach(Formula.implies(a("a"),a("b")),"S2");
        var question=cycle.ask(a("b"));
        var provider=new FaultyProvider();
        var options=new P23ProofRouting.Options(40,10_000,2,32);
        for(int i=0;i<100;i++) {
            cycle.proofRoutingReceipt(question.id(),provider,options);
            P23AnswerEvidence.inspect(cycle,question.id(),provider,options);
        }
        long[] direct=new long[240], enriched=new long[240];
        for(int i=0;i<direct.length;i++) {
            if(i%2==0) {
                long t=System.nanoTime();
                var x=cycle.proofRoutingReceipt(question.id(),provider,options).orElseThrow();
                direct[i]=System.nanoTime()-t;
                if(!x.proofChecked())throw new AssertionError("unverified direct receipt");
                t=System.nanoTime();
                var y=P23AnswerEvidence.inspect(cycle,question.id(),provider,options);
                enriched[i]=System.nanoTime()-t;
                if(!y.optionalRoute().orElseThrow().proofChecked())throw new AssertionError("unverified evidence");
            } else {
                long t=System.nanoTime();
                var y=P23AnswerEvidence.inspect(cycle,question.id(),provider,options);
                enriched[i]=System.nanoTime()-t;
                if(!y.optionalRoute().orElseThrow().proofChecked())throw new AssertionError("unverified evidence");
                t=System.nanoTime();
                var x=cycle.proofRoutingReceipt(question.id(),provider,options).orElseThrow();
                direct[i]=System.nanoTime()-t;
                if(!x.proofChecked())throw new AssertionError("unverified direct receipt");
            }
        }
        System.out.printf(java.util.Locale.ROOT,
            "OFFLINE_SYNTHETIC,BaselineRouting,p50_us=%.3f,p95_us=%.3f,n=%d%n",
            percentile(direct,0.5)/1000.0,percentile(direct,0.95)/1000.0,direct.length);
        System.out.printf(java.util.Locale.ROOT,
            "OFFLINE_SYNTHETIC,StructuredEvidence,p50_us=%.3f,p95_us=%.3f,n=%d%n",
            percentile(enriched,0.5)/1000.0,percentile(enriched,0.95)/1000.0,enriched.length);
    }
}
