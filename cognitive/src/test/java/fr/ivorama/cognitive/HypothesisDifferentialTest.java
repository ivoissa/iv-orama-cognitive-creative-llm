package fr.ivorama.cognitive;

import java.util.*;

/** Independent finite-world oracle for hypothesis entailment: no FCI implementation reused. */
public final class HypothesisDifferentialTest {
    public static void main(String[] args) {
        Random random=new Random(0x51FC1L);
        int cases=70, assertions=0;
        for(int trial=0;trial<cases;trial++) {
            int n=2+random.nextInt(3); // 2..4 exhaustive, exclusive hypotheses
            int m=1+random.nextInt(3); // 1..3 observed signals
            var engine=new HypothesisReasoningEngine("differential",true);
            String[] labels=new String[n];
            for(int i=0;i<n;i++) { labels[i]="h"+i; engine.candidate(labels[i]); }
            Boolean[][] predicts=new Boolean[n][m];
            Boolean[] observed=new Boolean[m];
            for(int i=0;i<n;i++) for(int j=0;j<m;j++) {
                if(random.nextInt(5)==0) continue; // partial rules
                boolean b=random.nextBoolean();
                predicts[i][j]=b;
                engine.predicts(labels[i],"s"+j,b);
            }
            for(int j=0;j<m;j++) {
                if(random.nextInt(4)==0) continue; // missing observations
                observed[j]=random.nextBoolean();
                engine.observe("s"+j,observed[j],"oracle-controlled input");
            }
            ArrayList<String> surviving=new ArrayList<>();
            for(int i=0;i<n;i++) {
                boolean possible=true;
                for(int j=0;j<m;j++) {
                    if(predicts[i][j]!=null && observed[j]!=null && !predicts[i][j].equals(observed[j])) {
                        possible=false; break;
                    }
                }
                if(possible) surviving.add(labels[i]);
            }
            HypothesisReasoningEngine.Finding actual=engine.evaluate();
            if(surviving.isEmpty()) {
                assertions++;
                if(actual.state()!=HypothesisReasoningEngine.State.INCONSISTENT)
                    throw new AssertionError("trial="+trial+" expected INCONSISTENT but "+actual);
            } else if(surviving.size()==1) {
                assertions++;
                if(actual.state()!=HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY ||
                        !actual.cause().equals(surviving.get(0)))
                    throw new AssertionError("trial="+trial+" expected "+surviving+" but "+actual);
            } else {
                assertions++;
                if(actual.state()!=HypothesisReasoningEngine.State.UNDETERMINED ||
                        !new HashSet<>(actual.possible()).equals(new HashSet<>(surviving)))
                    throw new AssertionError("trial="+trial+" expected "+surviving+" but "+actual);
            }
        }
        System.out.println("DIFFERENTIAL "+assertions+"/"+cases+" PASS (oracle d'énumération indépendant, graine fixe)");
    }
}
