package fr.ivorama.cognitive;

import java.util.*;

/** Offline microbenchmark only: fake provider, not an Ollama or LogicNG result. */
public final class P23RoutingBench {
    private static final int WARM = 80, SAMPLES = 200;
    private static final Formula P=Formula.atom("p"), Q=Formula.atom("q");
    private static final SemanticExplanationProvider HINT = new SemanticExplanationProvider() {
        public SemanticExplanation explain(Collection<SemanticExplanation.Premise> premises,Formula goal) {
            var chosen=premises.stream().filter(x->x.id().equals("P1")||x.id().equals("P2")).toList();
            return new SemanticExplanation(SemanticExplanation.Kind.IMPLICATION,SemanticExplanation.Status.COMPLETE,
                    ModelExplorer.Status.PROVEN_TRUE,Optional.of(goal),Optional.empty(),Optional.empty(),
                    List.of(new SemanticExplanation.Core(chosen,true,true)),true,0,0,0,"SIMULATED","Not LogicNG");
        }
        public boolean matches(SemanticExplanation e,Collection<SemanticExplanation.Premise> p,Formula q) {return true;}
    };
    public static void main(String[] args){
        if(args.length!=1 || (!args[0].equals("baseline")&&!args[0].equals("optin")))
            throw new IllegalArgumentException("Usage: baseline|optin");
        long[] micros=new long[SAMPLES];long provider=0;long alloc=0;int allocSamples=0;
        for(int i=0;i<WARM+SAMPLES;i++){
            var c=new IntegratedQuestionCycle();
            c.teach(P,"human");c.teach(Formula.implies(P,Q),"human");
            var question=c.ask(Q);
            var before=c.journal();
            long ns,providerNs=0,allocated=-1;
            if(args[0].equals("baseline")){
                long start=System.nanoTime();
                var report=c.question(question.id());
                ns=System.nanoTime()-start;
                if(report.answer().isEmpty()||!report.answer().orElseThrow())throw new AssertionError();
            }else{
                var m=P23RoutingTelemetry.measure(c,question.id(),HINT,
                        new P23ProofRouting.Options(128,10000,2,8));
                ns=m.totalWallNanos();providerNs=m.providerWallNanos();allocated=m.threadAllocatedBytes();
                if(!m.hasCheckedProof()||m.receipt().orElseThrow().route()!=P23ProofRouting.Route.GUIDED)
                    throw new AssertionError("Unverified or unguided simulation");
            }
            if(!c.journal().equals(before))throw new AssertionError("FCI journal changed");
            if(i>=WARM){int j=i-WARM;micros[j]=ns;provider+=providerNs;
                if(allocated>=0){alloc+=allocated;allocSamples++;}}
        }
        Arrays.sort(micros);
        long total=0;for(long x:micros)total+=x;
        System.out.printf(Locale.ROOT,"case=%s samples=%d mean_us=%.3f p50_us=%.3f p95_us=%.3f "
                +"provider_mean_us=%.3f thread_alloc_mean_bytes=%s%n",args[0],SAMPLES,
                total/1000.0/SAMPLES,micros[SAMPLES/2]/1000.0,micros[(int)(SAMPLES*0.95)]/1000.0,
                provider/1000.0/SAMPLES,allocSamples>0?String.format(Locale.ROOT,"%.1f",(double)alloc/allocSamples):"N/A");
    }
}
