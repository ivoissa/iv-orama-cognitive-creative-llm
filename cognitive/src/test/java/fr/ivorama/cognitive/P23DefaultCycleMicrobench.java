package fr.ivorama.cognitive;

/** Microbenchmark for unchanged default FCI path; not a general LLM-quality benchmark. */
public final class P23DefaultCycleMicrobench {
    public static void main(String[] args) {
        Formula p=Formula.atom("p"),q=Formula.atom("q");
        Formula implies=Formula.implies(p,q);
        final int warm=50, measured=150;
        long nanos=0;
        for(int i=0;i<warm+measured;i++) {
            long begin=System.nanoTime();
            var cycle=new IntegratedQuestionCycle();
            cycle.teach(p,"declared");
            cycle.teach(implies,"declared");
            var answer=cycle.ask(q);
            if(answer.answer().isEmpty() || !answer.answer().orElseThrow())
                throw new AssertionError("baseline decision changed");
            if(i>=warm) nanos+=System.nanoTime()-begin;
        }
        System.out.printf(java.util.Locale.ROOT,"default-cycle mean_us=%.3f iterations=%d%n",nanos/1000.0/measured,measured);
    }
}
