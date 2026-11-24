package fr.ivorama.cognitive;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Reproducible microbenchmark, not JMH. Allocated bytes are NOT retained heap.
 * Each invocation is a fresh JVM, fixed Xms/Xmx; retain raw samples.
 */
public final class P1BackendBenchmark {
    static Formula random(Random r,int depth) {
        if(depth==0) return r.nextInt(6)==5 ? Formula.Bottom.INSTANCE : Formula.atom("v"+r.nextInt(5));
        return switch(r.nextInt(5)) {
            case 0 -> Formula.and(random(r,depth-1),random(r,depth-1));
            case 1 -> Formula.implies(random(r,depth-1),random(r,depth-1));
            case 2 -> Formula.not(random(r,depth-1));
            case 3 -> Formula.iff(random(r,depth-1),random(r,depth-1));
            default -> random(r,0);
        };
    }
    static volatile Object sink;
    record Fixture(String name,List<Formula> premises,Formula goal) {}
    static List<Fixture> fixtures() {
        List<Fixture> fs=new ArrayList<>();
        var r=new Random(71203);List<Formula> small=new ArrayList<>();
        for(int i=0;i<4;i++)small.add(random(r,3));
        fs.add(new Fixture("small5",List.copyOf(small),Formula.atom("v0")));
        List<Formula> chain=new ArrayList<>();chain.add(Formula.atom("x0"));
        for(int i=0;i<63;i++)chain.add(Formula.implies(Formula.atom("x"+i),Formula.atom("x"+(i+1))));
        fs.add(new Fixture("chain64",List.copyOf(chain),Formula.atom("x63")));
        List<Formula> horn=new ArrayList<>();Formula all=Formula.atom("h0");horn.add(all);
        for(int i=1;i<24;i++){var a=Formula.atom("h"+i);horn.add(a);all=Formula.and(all,a);}
        horn.add(Formula.implies(all,Formula.atom("target")));
        fs.add(new Fixture("horn25",List.copyOf(horn),Formula.atom("target")));
        fs.add(new Fixture("inconsistent",List.of(Formula.atom("a"),Formula.not(Formula.atom("a"))),Formula.atom("z")));
        return fs;
    }
    public static void main(String[] args) {
        int warmup=100, iterations=200;String mode=args[0];
        ReasoningBackend backend=switch(mode){case "reference"->new ModelExplorerBackend();case "sat"->new LogicNgSatBackend();case "checked"->new CheckedReasoningBackend(new LogicNgSatBackend());default->throw new IllegalArgumentException();};
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);long tid=Thread.currentThread().threadId();
        System.out.println("mode,fixture,sample,iterations,ns_per_op,allocated_bytes_per_op,status,complete_worlds,true_witness,false_witness");
        for(var f:fixtures()){
            for(int i=0;i<warmup;i++)sink=backend.analyze(f.premises,f.goal,ModelExplorer.Budget.small());
            for(int sample=0;sample<3;sample++){
                long bytes=bean.getThreadAllocatedBytes(tid),start=System.nanoTime();ModelExplorer.Analysis result=null;
                for(int i=0;i<iterations;i++){result=backend.analyze(f.premises,f.goal,ModelExplorer.Budget.small());sink=result;}
                long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(tid)-bytes;
                System.out.printf(Locale.ROOT,"%s,%s,%d,%d,%.1f,%.1f,%s,%s,%s,%s%n",mode,f.name,sample,iterations,(double)elapsed/iterations,(double)allocated/iterations,result.status(),result.completeWorlds(),result.trueWitness().isPresent(),result.falseWitness().isPresent());
            }
        }
    }
}
