package fr.ivorama.cognitive;

import java.lang.management.ManagementFactory;
import java.util.*;

/** Same class can run against baseline classes or P1 classes. No optional library needed. */
public final class P1CycleBenchmark {
    static volatile Object sink;
    static IntegratedQuestionCycle.Report run() {
        var c=new IntegratedQuestionCycle();Formula a=Formula.atom("a"),b=Formula.atom("b");
        c.teach(a,"declared");c.teach(Formula.implies(a,b),"rule");return c.ask(b);
    }
    public static void main(String[] args){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        for(int i=0;i<100;i++)sink=run();
        System.out.println("version,sample,iterations,ns_per_op,allocated_bytes_per_op,status,proof_checked");
        for(int sample=0;sample<3;sample++){
            long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),start=System.nanoTime();
            IntegratedQuestionCycle.Report result=null;for(int i=0;i<200;i++){result=run();sink=result;}
            long elapsed=System.nanoTime()-start,allocated=bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes;
            System.out.printf(Locale.ROOT,"%s,%d,200,%.1f,%.1f,%s,%s%n",args[0],sample,elapsed/200.0,allocated/200.0,result.status(),result.proofChecked());
        }
    }
}
