package fr.ivorama.cognitive;
/** Reflective RED/GREEN: compiles against old and new code. */
public final class P03RedProbe {
    public static void main(String[] args) throws Exception {
        boolean bounded=java.util.Arrays.stream(ProofChecker.class.getDeclaredMethods()).anyMatch(m->
                m.getName().equals("verify") && m.getParameterCount()==3);
        boolean search=java.util.Arrays.stream(ProofSearch.class.getDeclaredMethods()).anyMatch(m->
                m.getName().equals("findWithBudget") && m.getParameterCount()==4);
        if(!bounded || !search) throw new AssertionError("P0.3 missing global budgets: checker="+bounded+" search="+search);
        System.out.println("P0.3 RED/GREEN PASS");
    }
}
