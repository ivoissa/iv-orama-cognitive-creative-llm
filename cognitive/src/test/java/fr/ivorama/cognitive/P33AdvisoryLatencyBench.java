package fr.ivorama.cognitive;
import java.nio.file.*;
import java.util.*;
/** Microbenchmark, offline deterministic fixtures: overhead only, NOT real LLM latency. */
public final class P33AdvisoryLatencyBench {
    private static long percentile(long[] samples,int pct){
        long[] a=samples.clone();Arrays.sort(a);
        return a[Math.min(a.length-1,(int)Math.ceil(a.length*pct/100.0)-1)];
    }
    private static record Fixture(String model,String reference,String draft){}
    public static void main(String[] args)throws Exception{
        var lines=Files.readAllLines(Path.of("evaluation/p33/provider-independent-oracles.tsv"));
        List<Fixture> cases=new ArrayList<>();
        for(var line:lines.subList(1,lines.size())){
            String[] f=line.split("\t",-1);cases.add(new Fixture(f[1],f[2],f[3]));
        }
        ModelExplorer.Budget budget=ModelExplorer.Budget.small();
        for(int i=0;i<18;i++)for(var c:cases){
            var review=P31CandidateAnswerAudit.inspect(c.model,c.reference,c.draft,budget);
            P32SuggestedCorrections.propose(review,budget);
        }
        int rounds=35;int size=rounds*cases.size();
        long[] baseline=new long[size], audit=new long[size], advice=new long[size];int k=0;
        for(int i=0;i<rounds;i++) for(var c:cases){
            long started=System.nanoTime();
            String preserved=c.draft;
            baseline[k]=System.nanoTime()-started;
            started=System.nanoTime();
            var r=P31CandidateAnswerAudit.inspect(c.model,c.reference,preserved,budget);
            audit[k]=System.nanoTime()-started;
            started=System.nanoTime();
            var recommendation=P32SuggestedCorrections.propose(r,budget);
            advice[k]=System.nanoTime()-started;
            if(recommendation.permitsAutomaticReplacement())throw new AssertionError("Unsafe rewrite");
            k++;
        }
        System.out.printf(Locale.ROOT,"P33,SIMULATED_ONLY,runs=%d,baseline_identity_p50_us=%.2f,inspect_p50_us=%.2f,inspect_p95_us=%.2f,advice_p50_us=%.2f,advice_p95_us=%.2f%n",
            size,percentile(baseline,50)/1000.0,percentile(audit,50)/1000.0,
            percentile(audit,95)/1000.0,percentile(advice,50)/1000.0,
            percentile(advice,95)/1000.0);
    }
}
