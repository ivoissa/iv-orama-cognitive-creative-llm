package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.ModelExplorer;
import fr.ivorama.cognitive.P31CandidateAnswerAudit;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Paired synthetic integration benchmark: fixtures are NOT real model completions. */
public final class P32ObserveFixtureBench {
    private record Fixture(String id,String provider,String facts,String draft,P31CandidateAnswerAudit.Status expected) { }
    private static long percentile(long[] values,int p) {
        long[] c=values.clone();Arrays.sort(c);return c[Math.min(c.length-1,Math.max(0,(int)Math.ceil(c.length*p/100.0)-1))];
    }
    public static void main(String[] args) throws Exception {
        var lines=Files.readAllLines(Path.of("evaluation/p31/model-agnostic-fixtures.tsv"));
        var cases=new ArrayList<Fixture>();
        for(String row:lines.subList(1,lines.size())){
            String[] c=row.split("\t",-1);
            if(c.length!=5)throw new AssertionError("Invalid fixture schema");
            cases.add(new Fixture(c[0],c[1],c[2],c[3],P31CandidateAnswerAudit.Status.valueOf(c[4])));
        }
        int iterations=100;
        long[] noAudit=new long[cases.size()*iterations],observed=new long[noAudit.length];
        int index=0;
        int supported=0,contradictions=0,unresolved=0;
        for(int warm=0;warm<10;warm++)for(var c:cases)run(c,false);
        for(int warm=0;warm<10;warm++)for(var c:cases)run(c,true);
        for(int round=0;round<iterations;round++) for(var c:cases){
            long start=System.nanoTime();run(c,false);noAudit[index]=System.nanoTime()-start;
            start=System.nanoTime();var review=run(c,true);observed[index]=System.nanoTime()-start;
            if(round==0){
                if(review.fullySupported())supported++;
                if(review.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.CONTRADICTED)contradictions++;
                if(review.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE)unresolved++;
            }
            index++;
        }
        System.out.printf(Locale.ROOT,"P32,SIMULATED,fixtures=%d,supported=%d,contradictions=%d,unresolved=%d,baseline_p50_us=%.2f,observe_p50_us=%.2f,baseline_p95_us=%.2f,observe_p95_us=%.2f%n",
                cases.size(),supported,contradictions,unresolved,percentile(noAudit,50)/1000.0,
                percentile(observed,50)/1000.0,percentile(noAudit,95)/1000.0,percentile(observed,95)/1000.0);
    }
    private static ObservedGenerationBackend.Observation run(Fixture c,boolean observe) {
        GenerationBackend plain=(messages,stream)->new GenerationBackend.Result(c.draft,c.provider,"STOP",1,-1,null,null);
        if(!observe){
            if(!plain.generate(List.of(),part->{}).text().equals(c.draft))throw new AssertionError("baseline output");
            return null;
        }
        var found=new ArrayList<ObservedGenerationBackend.Observation>(1);
        var adapter=new ObservedGenerationBackend(plain,c.facts,ModelExplorer.Budget.small(),found::add);
        if(!adapter.generate(List.of(),part->{}).text().equals(c.draft))throw new AssertionError("output changed");
        if(found.size()!=1||found.getFirst().review().orElseThrow().status()!=c.expected)
            throw new AssertionError("unexpected result "+c.id);
        return found.getFirst();
    }
}
