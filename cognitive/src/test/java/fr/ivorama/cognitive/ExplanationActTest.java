package fr.ivorama.cognitive;

import java.util.*;

/** IVOC-LING-005 : tests de développement, entièrement séparés des 100 questions EVAL-001. */
public final class ExplanationActTest {
    private static int count;
    private static void check(boolean value,String message) {
        count++;
        if(!value) throw new AssertionError("LING-005 : "+message);
    }
    private static FrenchQuestionDialogue dialogue() {
        var d=new FrenchQuestionDialogue();
        d.declare("document","A");
        return d;
    }
    public static int runAll() {
        count=0;
        var d=dialogue();
        String conditional=d.teach("Si le document A est validé, alors le document A est archivé.").get(0);
        String fact=d.teach("Le document A est validé.").get(0);
        int premises=d.cycle().premises().size();
        var why=d.ask("Pourquoi le document A est-il archivé ?");
        check(why.questionId().equals("Q1"),"explanation creates exactly one Q");
        check(!why.pending() && why.reportOptional().isEmpty(),
                "explained WHY is closed without inventing a yes/no report");
        check(why.text().contains("PROVEN_CONDITIONALLY"),"checked explanation classified conditional");
        check(why.text().contains("modus ponens"),"proof rendered using actual rule");
        check(why.text().contains("non vérifiées indépendamment"),"no imaginary empirical certification");
        check(d.cycle().questionAct("Q1").state()==IntegratedQuestionCycle.ActState.EXPLANATION,"typed explanation Q");
        check(d.cycle().questionAct("Q1").formula().isPresent(),"explanation has a semantic goal");
        check(d.cycle().questions().isEmpty(),"no hidden yes/no question");
        check(d.cycle().premises().size()==premises,"no facts created by an explanation Q");
        check(d.cycle().questionActCount()==1,"one interrogative act");
        d.retract(fact);
        check(d.status("Q1").text().contains("NOT_ESTABLISHED"),"retraction invalidates explanation");
        check(d.status("Q1").pending(),"retraction reopens explanatory obligation");
        check(d.cycle().questionActCount()==1,"revision does not create new Q");
        d.teach("Le document A est validé.");
        check(d.status("Q1").text().contains("PROVEN_CONDITIONALLY"),"alternative source restores explanation");
        check(!d.status("Q1").pending(),"restored explanation closes obligation");
        d.retract(conditional);
        check(d.status("Q1").text().contains("NOT_ESTABLISHED"),"lost implication removes explanation");
        var standalone=dialogue();
        standalone.teach("Le document A est archivé.");
        var direct=standalone.ask("Pourquoi le document A est-il archivé ?");
        check(direct.text().contains("DIRECT_ASSERTION_ONLY"),"repeating premise is not explanation");
        check(direct.pending(),"direct restatement cannot repair explanation Q");
        check(!direct.text().contains("Conclusion :"),"bare statement not misrepresented as derivation");
        var conflicting=dialogue();
        conflicting.teach("Le document A est archivé.");
        conflicting.teach("Le document A n'est pas archivé.");
        check(conflicting.ask("Pourquoi le document A est-il archivé ?").text().contains("INCONSISTENT"),
                "inconsistent context not licensed to explain everything");
        var noGoal=dialogue();
        check(noGoal.ask("Pourquoi le document A est-il archivé ?").text().contains("NOT_ESTABLISHED"),
                "unknown proposition not explained");
        var question=dialogue();
        var normal=question.ask("Le document A est-il archivé ?");
        check(question.cycle().questionAct(normal.questionId()).state()==IntegratedQuestionCycle.ActState.INTERPRETED,
                "yes/no semantics unchanged");
        var whyQuestion=question.ask("Pourquoi le document A est-il archivé ?");
        check(!normal.questionId().equals(whyQuestion.questionId()) && question.cycle().questionActCount()==2,
                "two different acts, each its own gap");
        check(question.cycle().journal().stream().filter(e->e.operation().equals("QUESTION_POSED")).count()==2,
                "no synthetic third act");

        var unknown=dialogue();
        var noParser=unknown.ask("Pourquoi la durée de vie des étoiles varie-t-elle ?");
        check(noParser.pending(),"unsupported Why remains open, not silently dropped");
        check(unknown.cycle().questionAct(noParser.questionId()).state()==IntegratedQuestionCycle.ActState.UNINTERPRETED,
                "uninterpreted question has distinct typed state");
        check(unknown.cycle().questionAct(noParser.questionId()).formula().isEmpty(),
                "no guessed Formula for unknown French");
        check(unknown.cycle().premises().isEmpty(),"invalid French never adds facts");
        check(unknown.cycle().questionActCount()==1,"one Q even if language unsupported");
        check(unknown.cycle().unresolvedQuestions().get(0).choices().isEmpty(),"not a spurious clarification");
        var removed=unknown.withdraw(noParser.questionId());
        check(removed.text().contains("retirée") && !removed.pending(),"uninterpreted Q can be terminally withdrawn");
        check(unknown.cycle().questionAct(noParser.questionId()).state()==IntegratedQuestionCycle.ActState.WITHDRAWN,
                "withdrawn history retained");
        check(unknown.cycle().journal().stream().anyMatch(e->e.operation().equals("QUESTION_UNINTERPRETED")),
                "failure audited");
        var incorrect=dialogue();
        var bad=incorrect.ask("Le document A est-il inconnue ?");
        check(incorrect.cycle().questionAct(bad.questionId()).state()==IntegratedQuestionCycle.ActState.UNINTERPRETED,
                "unsupported adjective cannot turn into a fact");

        var rdo=dialogue();
        String implication=rdo.teach("Si le document A est validé, alors le document A est archivé.").get(0);
        var howNoPlan=rdo.ask("Comment savoir si le document A est archivé ?");
        check(howNoPlan.text().contains("NO_PLAN"),"no imaginary sensor/procedure");
        check(rdo.cycle().questionAct(howNoPlan.questionId()).state()==IntegratedQuestionCycle.ActState.INVESTIGATION,
                "investigation has different Q type");
        check(rdo.cycle().premises().size()==1,"asking for observation makes no observation");
        rdo.registerObservable("Le document A est validé.");
        check(rdo.status(howNoPlan.questionId()).text().contains("PLAN_AVAILABLE"),
                "RDO yields explicit procedure after observable declaration");
        check(rdo.status(howNoPlan.questionId()).text().contains("aucune mesure réalisée"),
                "plan never confused with actual measurement");
        rdo.teach("Le document A est validé.");
        check(rdo.status(howNoPlan.questionId()).text().contains("ALREADY_DETERMINED"),
                "knowledge update changes procedure state, not Q identity");
        check(rdo.cycle().questionActCount()==1,"one independent how-to-know Q");
        rdo.retract(implication);
        check(rdo.status(howNoPlan.questionId()).text().contains("NO_PLAN") ||
                rdo.status(howNoPlan.questionId()).text().contains("PLAN_AVAILABLE"),
                "retraction reevaluates procedural status");
        // A question without the necessary interrogative marker is still not an Ask act.
        int before=rdo.cycle().questionActCount();
        boolean rejected=false;
        try { rdo.ask("Le document A est archivé."); } catch(IllegalArgumentException ok) { rejected=true; }
        check(rejected && rdo.cycle().questionActCount()==before,"assertion cannot masquerade as Q");
        return count;
    }
    public static void main(String[] args) {
        System.out.println("LING-005 OK - "+runAll()+" checks");
    }
}
