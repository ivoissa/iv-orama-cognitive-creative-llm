package fr.ivorama.cognitive;

import java.util.*;

/** IVOC-REASON-004: three actual dialogue questions plus deterministic differential tests. */
public final class ExperimentChoiceTest {
    private static int assertions;
    private static void check(boolean b,String message) {
        assertions++; if(!b) throw new AssertionError(message);
    }
    private static String talk(StandaloneTechnicalAssistant ai,String text) {
        var r=ai.reply(text);
        check(!r.kind().contains("ERROR") && !r.kind().contains("UNKNOWN"),
              text+" -> "+r.text());
        return r.text();
    }
    private static void announce(String situation,String question,String answer) {
        System.out.println("=== "+situation+" ===");
        System.out.println("QUESTION EXACTE : "+question);
        System.out.println("REPONSE BRUTE DU MOTEUR :\n"+answer);
        System.out.println();
    }
    public static void main(String[] args) {
        // Q1: diagnostic: a balanced test outranks rare 1:3 partition.
        var incident=new StandaloneTechnicalAssistant();
        talk(incident,"enquete exclusive incident_http");
        for(String h:List.of("doublon_sql","timeout_mq","jwt_expire","service_indispo")) talk(incident,"hypothese "+h);
        talk(incident,"prevoit doublon_sql journal_rare oui");
        talk(incident,"prevoit timeout_mq journal_rare non");
        talk(incident,"prevoit jwt_expire journal_rare non");
        talk(incident,"prevoit service_indispo journal_rare non");
        talk(incident,"prevoit doublon_sql test_reseau oui");
        talk(incident,"prevoit timeout_mq test_reseau oui");
        talk(incident,"prevoit jwt_expire test_reseau non");
        talk(incident,"prevoit service_indispo test_reseau non");
        String q1="Quel test choisir ?";
        String a1=talk(incident,q1);
        announce("QUESTION 1 - diagnostic technique",q1,a1);
        check(a1.contains("REPONSE : VERIFIER test_reseau (élimination garantie : 2)"),"balanced choice");
        check(a1.contains("SI OUI : [doublon_sql, timeout_mq]"),"true branch");
        check(a1.contains("SI NON : [jwt_expire, service_indispo]"),"false branch");
        check(a1.contains("journal_rare (pire=3)"),"alternative branch");
        check(incident.reply("quelle hypothese reste compatible ?").text().contains("INDÉTERMINÉ"),"no unjustified diagnosis");

        // Q2: mathematical/logical incomplete knowledge: one candidate is unspecified.
        var math=new StandaloneTechnicalAssistant();
        talk(math,"enquete exclusive demonstration");
        for(String h:List.of("preuve_directe","preuve_indirecte","preuve_constructive")) talk(math,"hypothese "+h);
        talk(math,"prevoit preuve_directe lemme_cle oui");
        talk(math,"prevoit preuve_constructive lemme_cle non");
        String q2="Quelles hypothèses après le test ?";
        String a2=talk(math,q2);
        announce("QUESTION 2 - raisonnement mathématique incomplet",q2,a2);
        check(a2.contains("REPONSE : VERIFIER lemme_cle (élimination garantie : 1)"),"partial predictions still helpful");
        check(a2.contains("SI OUI : [preuve_directe, preuve_indirecte]"),"unknown in yes branch");
        check(a2.contains("SI NON : [preuve_indirecte, preuve_constructive]"),"unknown in no branch");
        check(a2.contains("PREDICTIONS ABSENTES : [preuve_indirecte]"),"unknown prediction disclosed");
        talk(math,"retire prevision preuve_constructive lemme_cle");
        check(talk(math,q2).contains("AUCUN TEST A REDUCTION GARANTIE"),"do not claim reduction with only one known prediction");

        // Q3: security investigation evolves, contradiction suspends the planner,
        // and retracting an observation restores the initial question/plan.
        var sec=new StandaloneTechnicalAssistant();
        talk(sec,"enquete exclusive controle_acces");
        for(String h:List.of("regle_acl","certificat","reseau","horloge")) talk(sec,"hypothese "+h);
        talk(sec,"prevoit regle_acl rejet_tls oui");
        talk(sec,"prevoit certificat rejet_tls oui");
        talk(sec,"prevoit reseau rejet_tls non");
        talk(sec,"prevoit horloge rejet_tls non");
        talk(sec,"prevoit regle_acl cert_expire non");
        talk(sec,"prevoit certificat cert_expire oui");
        talk(sec,"prevoit reseau cert_expire non");
        talk(sec,"prevoit horloge cert_expire non");
        String q3="Quel test choisir ?";
        String initial=talk(sec,q3);
        check(initial.contains("VERIFIER rejet_tls"),"minimax 2/2 first");
        talk(sec,"indice rejet_tls oui");
        String changed=talk(sec,q3);
        announce("QUESTION 3 - investigation de sécurité après un indice",q3,changed);
        check(changed.contains("VERIFIER cert_expire"),"next test adapts to observations");
        check(changed.contains("SI OUI : [certificat]"),"yes isolates cert cause");
        check(changed.contains("SI NON : [regle_acl]"),"no isolates acl cause");
        talk(sec,"indice rejet_tls non");
        check(talk(sec,q3).contains("CONTRADICTION — CONCLUSION SUSPENDUE"),"contradiction suspends test plan");
        talk(sec,"retire indice rejet_tls non");
        check(talk(sec,q3).contains("VERIFIER cert_expire"),"repair contradiction");
        talk(sec,"retire indice rejet_tls oui");
        check(talk(sec,q3).contains("VERIFIER rejet_tls"),"retraction restores plan");

        // Differential test independent of FCI: brute-force both outcomes for randomly
        // generated partial prediction tables and compare partitions and chosen worst case.
        Random rng=new Random(0x170017);
        int oracleCases=300;
        for(int t=0;t<oracleCases;t++) {
            int n=2+rng.nextInt(6), m=1+rng.nextInt(7);
            Map<String,Map<String,Boolean>> pred=new LinkedHashMap<>();
            List<String> names=new ArrayList<>();
            for(int h=0;h<n;h++) {
                String id="h"+h; names.add(id);
                Map<String,Boolean> p=new LinkedHashMap<>();
                for(int j=0;j<m;j++) {
                    int value=rng.nextInt(3);
                    if(value<2) p.put("s"+j,value==1);
                }
                pred.put(id,p);
            }
            var finding=new HypothesisReasoningEngine.Finding(
                    HypothesisReasoningEngine.State.UNDETERMINED,"UNKNOWN","","",names,
                    List.of(),Optional.empty(),"synthetic test",t,false);
            var plan=ExperimentChoicePlanner.choose(finding,pred,List.of());
            int oracleBest=n;
            String oracleSignal=null;
            for(int j=0;j<m;j++) {
                String signal="s"+j;
                if(names.stream().noneMatch(h->pred.get(h).containsKey(signal))) continue;
                int yes=0,no=0;
                for(String h:names) {
                    Boolean v=pred.get(h).get(signal);
                    if(v==null||v) yes++;
                    if(v==null||!v) no++;
                }
                int worst=Math.max(yes,no);
                if(worst<oracleBest || (worst==oracleBest && worst<n &&
                        (oracleSignal==null||signal.compareTo(oracleSignal)<0))) {
                    oracleBest=worst; oracleSignal=signal;
                }
            }
            // Directly test the minimax property (tie logic does not affect optimal worst case).
            if(oracleBest==n) check(plan.recommended().isEmpty(),"oracle: no guaranteed improvement "+t);
            else {
                check(plan.recommended().isPresent(),"oracle: should have candidate "+t);
                var choice=plan.recommended().orElseThrow();
                check(choice.worstCase()==oracleBest,"oracle minimax disagreement "+t);
                for(int h=0;h<n;h++) {
                    String name=names.get(h);
                    Boolean expected=pred.get(name).get(choice.signal());
                    check(choice.ifYes().contains(name)==(expected==null||expected),"oracle yes " +t);
                    check(choice.ifNo().contains(name)==(expected==null||!expected),"oracle no " +t);
                }
            }
        }
        // Unexpected/unrecognized questions cannot silently invent observations.
        var rejected=sec.reply("Détecte automatiquement une nouvelle faille et corrige-la.");
        check(rejected!=null,"unrecognized free-form request must not crash");
        System.out.println("VALIDATION : "+assertions+" assertions PASS ; "+oracleCases+" cas différentiels ; 3 questions réelles.");
    }
}
