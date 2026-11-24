package fr.ivorama.cognitive;
import java.util.*;

/** 5000 jeux 2-CNF aléatoires à graine fixe contre énumération exhaustive. */
public final class TwoSatDifferentialTest {
    private static Formula literal(int i,boolean positive) {
        Formula f=Formula.atom("v"+i);
        return positive?f:Formula.not(f);
    }
    public static int runAll() {
        Random random=new Random(0x2C0FFEE);
        int checked=0;
        for(int trial=0;trial<5000;trial++) {
            int n=1+random.nextInt(7), clauses=1+random.nextInt(18);
            List<Formula> premises=new ArrayList<>();
            for(int j=0;j<clauses;j++) {
                int ai=random.nextInt(n); boolean positive=random.nextBoolean();
                Formula a=literal(ai,positive);
                if(random.nextBoolean()) premises.add(a);
                else {
                    Formula b=literal(random.nextInt(n),random.nextBoolean());
                    premises.add(Formula.implies(literal(ai,!positive),b));
                }
            }
            Formula q=literal(random.nextInt(n),random.nextBoolean());
            var oracle=ModelExplorer.analyzeFull(premises,q,ModelExplorer.Budget.small());
            var optimized=TwoSatEntailment.analyze(premises,q,List.of());
            if(optimized.isEmpty()) throw new AssertionError("2-CNF reconnu à tort comme non traité #"+trial);
            if(oracle.status()!=optimized.get().status())
                throw new AssertionError("2SAT divergence #"+trial+" oracle="+oracle.status()
                    +" actual="+optimized.get().status()+" prem="+premises+" q="+q);
            checked++;
            for(var w:List.of(optimized.get().trueWitness(),optimized.get().falseWitness())) if(w.isPresent()) {
                for(Formula premise:premises) if(!w.get().satisfies(premise))
                    throw new AssertionError("2SAT faux témoin #"+trial+" : "+premise);
                checked++;
            }
            if(optimized.get().trueWitness().isPresent() && !optimized.get().trueWitness().get().satisfies(q))
                throw new AssertionError("2SAT faux positif #"+trial);
            if(optimized.get().falseWitness().isPresent() && optimized.get().falseWitness().get().satisfies(q))
                throw new AssertionError("2SAT faux négatif #"+trial);
        }
        // Un problème très long mais en 2-CNF : pas d'énumération globale.
        List<Formula> chain=new ArrayList<>();
        chain.add(Formula.atom("c0"));
        for(int i=0;i<20000;i++) chain.add(Formula.implies(Formula.atom("c"+i),Formula.atom("c"+(i+1))));
        var result=ModelExplorer.analyze(chain,Formula.atom("c20000"),ModelExplorer.Budget.small());
        if(result.status()!=ModelExplorer.Status.PROVEN_TRUE || result.completeWorlds())
            throw new AssertionError("2-SAT longue chaîne non conclue exactement : "+result.status());
        checked++;
        return checked;
    }
    public static void main(String[] args) {System.out.println("2SAT DIFFERENTIAL OK - "+runAll()+" contrôles");}
}
