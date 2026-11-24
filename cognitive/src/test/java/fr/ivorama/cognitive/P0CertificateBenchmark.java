package fr.ivorama.cognitive;
/** Micro-benchmark indicative, not JMH; includes model exploration and proof search. */
public final class P0CertificateBenchmark {
  private static void query(){
    Formula p=Formula.atom("p"),q=Formula.atom("q");
    IntegratedQuestionCycle c=new IntegratedQuestionCycle();
    c.teach(p,"benchmark-fixture"); c.teach(Formula.implies(p,q),"benchmark-fixture");
    var result=c.ask(q);
    if(result.status()!=IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY || !result.proofChecked())
       throw new AssertionError("Invalid benchmark outcome");
  }
  public static void main(String[] args){
    for(int i=0;i<100;i++)query();
    for(int round=0;round<3;round++){
      long start=System.nanoTime();int n=200;
      for(int i=0;i<n;i++)query();
      System.out.printf(java.util.Locale.ROOT,"round_%d,%.3f,ms_per_query%n",round+1,(System.nanoTime()-start)/1_000_000.0/n);
    }
  }
}
