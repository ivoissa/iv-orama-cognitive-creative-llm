package fr.ivorama.cognitive;
import java.util.*;
/** Stable differential digest of 160 fixtures, meant to be run against P0.2 and P0.3 builds. */
public final class P03SupportDifferentialMain {
 public static void main(String[] args) {
   Random random = new Random(20261101);
   for(int i=0;i<160;i++) {
     List<Formula> atoms=new ArrayList<>();for(int k=0;k<6;k++)atoms.add(Formula.atom("x"+k));
     Set<Formula> context=new LinkedHashSet<>();
     int n=1+random.nextInt(7);
     for(int j=0;j<n;j++) {
       Formula a=atoms.get(random.nextInt(atoms.size()));
       Formula b=atoms.get(random.nextInt(atoms.size()));
       Formula p=switch(random.nextInt(4)) {
         case 0 -> a;
         case 1 -> Formula.implies(a,b);
         case 2 -> Formula.not(a);
         default -> Formula.and(a,b);
       };
       context.add(p);
     }
     Formula goal=atoms.get(random.nextInt(atoms.size()));
     var result=SupportAnalyzer.find(context,goal,ModelExplorer.Budget.small(),2048,64);
     List<String> canonical=new ArrayList<>();
     for(var one : result.minimalSupports()) {
       List<String> terms=one.stream().map(Object::toString).sorted().toList();
       canonical.add(String.join("&",terms));
     }
     Collections.sort(canonical);
     System.out.println(i+"|"+result.status()+"|"+result.examinedSubsets()+"|"+canonical);
   }
 }
}
