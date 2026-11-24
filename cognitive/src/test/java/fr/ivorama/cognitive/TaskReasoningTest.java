package fr.ivorama.cognitive;

import java.util.*;
/** IVOC-TASK-002: three different real tasks and adversarial invariants. */
public final class TaskReasoningTest {
    private static int count;
    private static void check(boolean condition,String message) {
        count++; if(!condition) throw new AssertionError(message);
    }
    private static StandaloneTechnicalAssistant.Response input(StandaloneTechnicalAssistant ai,String text) {
        var r=ai.reply(text);
        check(!r.kind().equals("TASK_ERROR"),"rejected command: "+text+" : "+r.text());
        return r;
    }
    public static void main(String[] args) {
        // Question 1: incident debugging. Graph is task-specific USER data, not a programmed answer.
        var a=new StandaloneTechnicalAssistant();
        input(a,"objectif cause_validee");
        input(a,"etape capturer_logs -> logs_obtenus si http500_signale");
        input(a,"etape identifier_erreur -> erreur_identifiee si logs_obtenus");
        input(a,"etape valider_cause -> cause_validee si erreur_identifiee");
        input(a,"observe http500_signale");
        var q1=input(a,"comment atteindre mon objectif ?");
        check(q1.kind().equals("TASK_PLAN"),"route HOW question");
        check(q1.text().contains("FCI Q1 : UNKNOWN"),"open FCI Q1");
        check(q1.text().contains("PLAN_AVAILABLE"),"three-step plan");
        check(q1.text().contains("[capturer_logs, identifier_erreur, valider_cause]"),"ordered actions");
        check(q1.questionStillOpen(),"plan not proof");
        var bad=a.reply("atteste valider_cause");
        check(bad.kind().equals("TASK_ERROR")&&bad.text().contains("non établi"),"cannot skip prerequisites");
        input(a,"atteste capturer_logs");
        input(a,"atteste identifier_erreur");
        check(input(a,"plan ?").text().contains("UNKNOWN"),"still not resolved");
        input(a,"atteste valider_cause");
        var achieved=input(a,"plan ?");
        check(achieved.text().contains("FCI Q1 : YES"),"attested goal closes FCI conditionally");
        check(achieved.text().contains("ESTABLISHED_CONDITIONALLY"),"conditional not independently checked");
        check(!achieved.questionStillOpen(),"closed after direct attestation");
        input(a,"retire http500_signale");
        var reopened=input(a,"plan ?");
        check(reopened.text().contains("FCI Q1 : UNKNOWN") && reopened.text().contains("réouverte : true"),"retracted evidence reopens same FCI");
        check(reopened.text().contains("http500_signale"),"missing evidence now displayed");
        check(reopened.text().contains("[capturer_logs, identifier_erreur, valider_cause]"),"all invalidated");

        // Question 2: coding, explicit prerequisites and lack of verification.
        var b=new StandaloneTechnicalAssistant();
        input(b,"objectif correctif_valide");
        input(b,"etape compiler -> compilation_ok si patch_ecrit");
        input(b,"etape tester -> tests_ok si compilation_ok");
        input(b,"etape approuver -> correctif_valide si tests_ok");
        var q2=input(b,"l'objectif est-il atteint ?");
        check(q2.kind().equals("TASK_STATUS"),"route STATUS question");
        check(q2.text().contains("NEED_OBSERVATION"),"cannot invent patch");
        check(q2.text().contains("[patch_ecrit]"),"asks user for patch");
        check(q2.text().contains("[compiler, tester, approuver]"),"ordered verification plan");
        input(b,"observe patch_ecrit");
        check(input(b,"plan ?").text().contains("PLAN_AVAILABLE"),"observation unblocks plan");
        input(b,"atteste compiler");
        input(b,"atteste tester");
        var preApproval=input(b,"plan ?");
        check(preApproval.text().contains("UNKNOWN"),"testing report does not mean approval");
        input(b,"atteste approuver");
        check(input(b,"plan ?").text().contains("YES"),"attested approval");
        input(b,"annule tester");
        var invalidated=input(b,"plan ?");
        check(invalidated.text().contains("UNKNOWN") && invalidated.text().contains("réouverte : true"),"withdraw tests invalidates approval and reopens FCI");
        check(invalidated.text().contains("[tester, approuver]"),"dependency chain recalculated");

        // Question 3: mathematics, explicit contradiction detection and no explosion.
        var c=new StandaloneTechnicalAssistant();
        input(c,"objectif conclusion_demontree");
        input(c,"etape etablir_hypotheses -> hypotheses_ok si donnees_disponibles");
        input(c,"etape calculer -> calcul_ok si hypotheses_ok");
        input(c,"etape verifier_preuve -> conclusion_demontree si calcul_ok");
        input(c,"observe non calcul_ok");
        var q3=input(c,"qu'est-ce qui bloque ?");
        check(q3.kind().equals("TASK_OBSTACLES"),"route WHY question");
        check(q3.text().contains("FCI Q1 : UNKNOWN"),"goal cannot follow from denial of intermediate fact");
        check(q3.text().contains("CONTRADICTED"),"plan blocked explicitly");
        check(q3.text().contains("calcul_ok (contredit)"),"explicit blocking obstacle");
        var premature=c.reply("atteste verifier_preuve");
        check(premature.kind().equals("TASK_ERROR"),"cannot attest negated input");
        input(c,"observe calcul_ok");
        var inconsistent=input(c,"plan ?");
        check(inconsistent.text().contains("INCONSISTENT"),"contradiction is not arbitrary YES");
        input(c,"retire calcul_ok");
        var repaired=input(c,"plan ?");
        check(repaired.text().contains("UNKNOWN"),"repaired knowledge still lacks proof");
        check(!repaired.text().contains("INCONSISTENT"),"conflict withdrawn");

        // Cross-domain adversarial checks on core engine, not just output substrings.
        var cycle=new TaskReasoningEngine();
        cycle.setGoal("a"); cycle.define("step_a","a","b"); cycle.define("step_b","b","a");
        check(cycle.evaluate().state()==TaskReasoningEngine.State.CYCLIC_PLAN,"cycle must be reported");
        check(cycle.evaluate().orderedActions().isEmpty(),"cycle must not create executable plan");
        var leaf=new TaskReasoningEngine(); leaf.setGoal("x");
        check(leaf.evaluate().state()==TaskReasoningEngine.State.NEED_OBSERVATION,"unknown leaf");
        check(leaf.evaluate().missingObservations().equals(List.of("x")),"unknown goal observation");
        leaf.observe("x",false,"explicit negative");
        check(leaf.evaluate().state()==TaskReasoningEngine.State.CONTRADICTED,"negative goal");
        leaf.withdraw("x",false);
        check(leaf.evaluate().state()==TaskReasoningEngine.State.NEED_OBSERVATION,"reopening negative goal");
        leaf.observe("x",true,"first observation");
        check(leaf.evaluate().state()==TaskReasoningEngine.State.ESTABLISHED_CONDITIONALLY,"direct goal");
        leaf.withdraw("x",true);
        check(leaf.evaluate().state()==TaskReasoningEngine.State.NEED_OBSERVATION,"direct withdrawal");
        boolean rejected=false;
        try { cycle.define("step_c","a","b"); } catch(IllegalArgumentException e){ rejected=true; }
        check(rejected,"duplicate effect cannot create nondeterministic plan");
        check(cycle.journal().size()==3,"failed mutation should not update ledger");
        rejected=false;
        try { cycle.observe("x;drop",true,"untrusted"); } catch(IllegalArgumentException e){ rejected=true; }
        check(rejected,"reject non-atom injection");
        check(cycle.journal().size()==3,"invalid input atomicity");
        var control=new StandaloneTechnicalAssistant();
        check(control.reply("Explique simplement la différence entre JPA et Hibernate.")
                .kind().equals("EXPLICATION_VERIFIEE"),"0.6.11 compatibility");
        check(control.reply("Écris une méthode Java 17 pour compter les chaînes non vides.")
                .kind().equals("CODE_CONTROLE"),"0.6.11 Java compatibility");
        System.out.println("=== 3 QUESTIONS RÉELLES — IV-ORAMA 0.6.12 ===");
        System.out.println("QUESTION 1 — Diagnostic : comment atteindre mon objectif ? [cause_validee]");
        System.out.println("RÉPONSE MOTEUR :\n"+q1.text());
        System.out.println("APRÈS TROIS ATTESTATIONS :\n"+achieved.text());
        System.out.println("APRÈS RETRAIT DE LA PREUVE INITIALE :\n"+reopened.text());
        System.out.println();
        System.out.println("QUESTION 2 — Programmation : l'objectif est-il atteint ? [correctif_valide]");
        System.out.println("RÉPONSE MOTEUR :\n"+q2.text());
        System.out.println("APRÈS ANNULATION DU TEST :\n"+invalidated.text());
        System.out.println();
        System.out.println("QUESTION 3 — Mathématiques : qu'est-ce qui bloque ? [conclusion_demontree]");
        System.out.println("RÉPONSE MOTEUR :\n"+q3.text());
        System.out.println("APRÈS OBSERVATIONS CONTRADICTOIRES :\n"+inconsistent.text());
        System.out.println("CHECKS "+count+"/"+count+" PASS");
    }
}
