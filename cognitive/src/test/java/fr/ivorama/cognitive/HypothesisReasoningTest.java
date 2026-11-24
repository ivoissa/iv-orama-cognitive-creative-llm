package fr.ivorama.cognitive;

import java.util.*;

/** Real end-to-end Java questions, deterministic adversarial checks. */
public final class HypothesisReasoningTest {
    private static int count;
    private static void check(boolean b,String reason) {
        count++; if(!b) throw new AssertionError(reason);
    }
    private static StandaloneTechnicalAssistant.Response send(StandaloneTechnicalAssistant ai,String text) {
        var response=ai.reply(text);
        check(!response.kind().contains("ERROR")&&!response.kind().contains("UNKNOWN"),
                "commande refusée: "+text+" => "+response.text());
        return response;
    }
    public static void main(String[] args) {
        // 1: investigate an API failure. These relations are the user's assumptions,
        // not automatically derived from the words "SQLSTATE 23505".
        var api=new StandaloneTechnicalAssistant();
        send(api,"enquete exclusive incident_500");
        send(api,"hypothese doublon_sql");
        send(api,"hypothese timeout_mq");
        send(api,"hypothese jeton_invalide");
        send(api,"prevoit doublon_sql sql23505 oui");
        send(api,"prevoit timeout_mq sql23505 non");
        send(api,"prevoit jeton_invalide sql23505 non");
        var before=send(api,"quelle hypothèse reste compatible ?");
        check(before.text().contains("INDÉTERMINÉ"),"no evidence -> unknown");
        send(api,"indice sql23505 oui");
        var q1=send(api,"Quelle hypothèse reste compatible ?");
        check(q1.text().contains("REPONSE : doublon_sql (DÉDUIT CONDITIONNELLEMENT"),"conditional proof");
        check(q1.text().contains("FCI : YES"),"FCI hypothesis established");
        check(q1.text().contains("timeout_mq")&&q1.text().contains("jeton_invalide"),"refuted alternatives");
        check(!q1.questionStillOpen(),"logical conditional closure");
        send(api,"retire indice sql23505 oui");
        var reopening=send(api,"peut-on conclure la cause ?");
        check(reopening.text().contains("INDÉTERMINÉ")&&reopening.text().contains("réouverture : true"),
                "FCI reopened after withdrawal");
        check(reopening.questionStillOpen(),"no proof after withdrawal");

        // 2: affirming the consequent is invalid (two hypotheses predict same signal).
        var math=new StandaloneTechnicalAssistant();
        send(math,"enquete exclusive exemple_logique");
        send(math,"hypothese panne_a");
        send(math,"hypothese panne_b");
        send(math,"prevoit panne_a voyant_rouge oui");
        send(math,"prevoit panne_b voyant_rouge oui");
        send(math,"indice voyant_rouge oui");
        var q2=send(math,"Peut-on conclure la cause ?");
        check(q2.text().contains("REPONSE : INDÉTERMINÉ"),"no affirming consequent");
        check(q2.text().contains("[panne_a, panne_b]")&&q2.questionStillOpen(),"two compatible models");
        check(q2.text().contains("FCI : UNKNOWN"),"not derived");

        // 3: maximize worst-case elimination with an explicit balanced binary test.
        var triage=new StandaloneTechnicalAssistant();
        send(triage,"enquete exclusive probleme_service");
        for(String h:List.of("h1","h2","h3","h4")) send(triage,"hypothese "+h);
        send(triage,"prevoit h1 test_rare oui");
        send(triage,"prevoit h2 test_rare non");
        send(triage,"prevoit h3 test_rare non");
        send(triage,"prevoit h4 test_rare non");
        send(triage,"prevoit h1 test_equilibre oui");
        send(triage,"prevoit h2 test_equilibre oui");
        send(triage,"prevoit h3 test_equilibre non");
        send(triage,"prevoit h4 test_equilibre non");
        var q3=send(triage,"Quelle observation vérifier ensuite ?");
        check(q3.text().contains("REPONSE : VÉRIFIER test_equilibre"),"2-2 split beats 1-3");
        check(q3.text().contains("FCI : UNKNOWN"),"question remains open");
        send(triage,"indice test_equilibre oui");
        check(send(triage,"Peut-on conclure la cause ?").text().contains("INDÉTERMINÉ"),"two survivors");
        send(triage,"indice test_equilibre non");
        var contradiction=send(triage,"Peut-on conclure la cause ?");
        check(contradiction.text().contains("PRÉMISSES CONTRADICTOIRES") && contradiction.text().contains("INCONSISTENT"),
                "global contradiction, no explosion");
        send(triage,"retire indice test_equilibre non");
        check(send(triage,"Peut-on conclure la cause ?").text().contains("INDÉTERMINÉ"),"repaired inconsistency");

        // Input hardening and provenance; rejected mutations leave logical states unchanged.
        var engine=new HypothesisReasoningEngine("case_test",true);
        engine.candidate("a"); engine.candidate("b");
        var baseline=engine.evaluate();
        var isolated=new HypothesisReasoningEngine("singleton",true);
        isolated.candidate("unique");
        check(isolated.evaluate().answer().equals("UNKNOWN"),"incomplete candidate catalogue cannot self-certify");
        boolean rejected=false;
        try { engine.predicts("unknown","signal",true); } catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"unknown hypothesis rejected");
        rejected=false;
        try { engine.observe("signal;sql",true,"user"); } catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"untrusted atom rejected");
        rejected=false;
        try { engine.predicts("a","signal",true); engine.predicts("a","signal",false); }
        catch(IllegalArgumentException e) { rejected=true; }
        check(rejected,"cannot duplicate contradictory prediction");
        check(engine.evaluate().state()==HypothesisReasoningEngine.State.UNDETERMINED,"invalid mutation has no deduction");
        engine.predicts("b","signal",false);
        engine.observe("signal",true,"user-supplied log");
        check(engine.evaluate().state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY,"propagated implications");
        engine.withdrawPrediction("b","signal");
        check(engine.evaluate().state()==HypothesisReasoningEngine.State.UNDETERMINED,"rule retraction reopens alternative");
        engine.withdraw("signal",true);
        check(engine.evaluate().state()==HypothesisReasoningEngine.State.UNDETERMINED,"observation retraction");
        check(baseline.answer().equals("UNKNOWN"),"baseline isolated");
        var old=new StandaloneTechnicalAssistant();
        check(old.reply("Explique simplement la différence entre JPA et Hibernate.").kind().equals("EXPLICATION_VERIFIEE"),"existing explanation not damaged");
        check(old.reply("Écris une méthode Java 17 pour compter les chaînes non vides.").kind().equals("CODE_CONTROLE"),"existing code not damaged");

        System.out.println("=== IV-ORAMA 0.6.13 : TROIS QUESTIONS AU MOTEUR JAVA ===");
        System.out.println("QUESTION 1 : Quelle hypothèse reste compatible ? (incident HTTP 500, indice sql23505=oui)");
        System.out.println("RÉPONSE EXACTE DU MOTEUR :\n"+q1.text());
        System.out.println("CONTRE-TEST : retrait de l'indice\n"+reopening.text());
        System.out.println();
        System.out.println("QUESTION 2 : Peut-on conclure la cause ? (deux causes prédisent le même voyant)");
        System.out.println("RÉPONSE EXACTE DU MOTEUR :\n"+q2.text());
        System.out.println();
        System.out.println("QUESTION 3 : Quelle observation vérifier ensuite ? (4 hypothèses, deux tests)");
        System.out.println("RÉPONSE EXACTE DU MOTEUR :\n"+q3.text());
        System.out.println("CONTRE-TEST : observations contradictoires\n"+contradiction.text());
        System.out.println("ASSERTIONS "+count+"/"+count+" PASS");
    }
}
