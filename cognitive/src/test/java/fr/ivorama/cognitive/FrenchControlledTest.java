package fr.ivorama.cognitive;

import java.util.*;

/** Tests de développement ; ne font PAS partie des 100 questions indépendantes. */
public final class FrenchControlledTest {
    private FrenchControlledTest() {}
    private static int n;
    private static void check(boolean b, String why) { n++; if(!b) throw new AssertionError(why); }
    private static void reject(Runnable action, String why) {
        boolean rejected=false;
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException e) { rejected=true; }
        check(rejected,why);
    }
    public static int runAll() {
        n=0;
        var fr=new ControlledFrench();
        fr.declareEntity("document","A");
        fr.declareEntity("document","B");
        var fact=fr.interpret("Le document A est validé.");
        check(fact.act()==ControlledFrench.Act.ASSERT,"fact act");
        check(fact.formulas().get(0).equals(Formula.atom("approuve_document_a")),"validated is not 'valide'");
        check(fr.interpret("Le document A est valide.").formulas().get(0)
                .equals(Formula.atom("valide_document_a")),"valid is different from approved");
        check(fr.interpret("Le document A est-il validé ?").formulas().equals(fact.formulas()),"inversion parse equality");
        check(fr.interpret("Est-ce que le document A est validé ?").formulas().equals(fact.formulas()),"est-ce que");
        reject(() -> fr.interpret("Pourquoi le document A est-il validé ?"),"why cannot be reduced to yes/no");
        check(fr.interpret("Le document A n'est pas archivé.").formulas().get(0)
                .equals(Formula.not(Formula.atom("archive_document_a"))),"negated fact");
        check(fr.interpret("Est-ce que le document A n'est pas archivé ?").formulas().get(0)
                .equals(Formula.not(Formula.atom("archive_document_a"))),"negated question");
        check(fr.interpret("Si le document A est validé, alors le document A est archivé.")
                .formulas().equals(List.of(Formula.implies(Formula.atom("approuve_document_a"),
                        Formula.atom("archive_document_a")))),"local implication");
        var all=fr.interpret("Tous les documents validés sont archivés.");
        check(all.act()==ControlledFrench.Act.UNIVERSAL && all.formulas().size()==2,"finite grounding");
        check(fr.frozenKinds().isEmpty(),"parsing does not freeze domain before commit");
        reject(() -> fr.interpret("Tous les dossiers validés sont archivés."),"no empty quantification");
        fr.commit(all);
        check(fr.frozenKinds().equals(Set.of("document")),"commit locks quantified kind");
        reject(() -> fr.declareEntity("document","C"),"no silent domain extension");
        reject(() -> fr.declareEntity("document","A"),"domain lock includes duplicate");
        reject(() -> fr.interpret("Le document Z est validé."),"undeclared entity rejected");
        reject(() -> fr.interpret("Le document A est magnifique."),"unknown adjective rejected");
        reject(() -> fr.interpret("Le document A est valide ou archivé ?"),"ambiguity rejected");
        reject(() -> fr.interpret("La document A est validé."),"incorrect article rejected");
        reject(() -> fr.interpret("Qui est le responsable du document A ?"),"free question rejected");
        reject(() -> fr.interpret("Le document A est peut-être archivé."),"modality not silently dropped");
        check(fr.questionFor("document","A","archivé")
                .equals("Le document A est-il archivé ?"),"generative surface roundtrip");
        for (String kind : List.of("document","dossier","porte","objet")) {
            var isolated=new ControlledFrench(); isolated.declareEntity(kind,"A");
            for (String prop:List.of("disponible","complet","archivé","validé")) {
                String generated=isolated.questionFor(kind,"A",prop);
                check(isolated.interpret(generated).act()==ControlledFrench.Act.ASK,"generated question typed: "+generated);
                check(isolated.interpret(generated).formulas().equals(isolated.interpret(generated).formulas()),
                        "parse deterministic");
            }
        }
        var dialog=new FrenchQuestionDialogue();
        dialog.declare("document","A");
        String universalId=dialog.teach("Tous les documents validés sont archivés.").get(0);
        String factId=dialog.teach("Le document A est validé.").get(0);
        var r=dialog.ask("Le document A est-il archivé ?");
        check(r.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"answer conditional");
        check(r.report().answer().orElseThrow(),"answer yes");
        check(r.report().proofChecked(),"checked proof");
        check(r.text().contains("sous réserve") && !r.text().contains("certifié empiriquement"),"epistemic guard");
        dialog.retract(factId);
        check(dialog.status(r.questionId()).report().reopened(),"question reopened");
        check(dialog.status(r.questionId()).report().status()==IntegratedQuestionCycle.Status.OPEN,"unknown after withdrawal");
        check(dialog.cycle().journal().stream().anyMatch(e->e.operation().equals("QUESTION_REOPENED")),"journal reopens");
        check(dialog.cycle().premises().stream().anyMatch(p->p.id().equals(universalId)),"universal remains");
        var untranslated=dialog.ask("Le document A est-il fantastique ?");
        check(untranslated.pending() && dialog.cycle().questionAct(untranslated.questionId()).state()==
              IntegratedQuestionCycle.ActState.UNINTERPRETED,"unknown expression is a recorded uninterpreted Q");
        check(dialog.cycle().questions().size()==1,"uninterpreted question never creates fictitious logical report");

        var observations=new FrenchQuestionDialogue();
        observations.declare("porte","B");
        String implication=observations.teach("Si la porte B est ouverte, alors la porte B est disponible.").get(0);
        check(!implication.isBlank(),"if then rule taught");
        observations.registerObservable("La porte B est ouverte.");
        var pending=observations.ask("La porte B est-elle disponible ?");
        check(pending.report().status()==IntegratedQuestionCycle.Status.NEED_INFORMATION,"RDO request");
        check(pending.text().contains("Information à demander"),"RDO request expressed");
        var completed=observations.answerObservation(pending.questionId(),true,"déclaré, non vérifié");
        check(completed.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"repair after observation");
        check(completed.text().contains("sous réserve"),"source not verified");

        var negatives=new FrenchQuestionDialogue();
        negatives.declare("document","A");
        negatives.teach("Le document A n'est pas archivé.");
        var nresponse=negatives.ask("Le document A est-il archivé ?");
        check(nresponse.report().answer().equals(Optional.of(false)),"negative grounded answer");
        var nquestion=negatives.ask("Est-ce que le document A n'est pas archivé ?");
        check(nquestion.report().answer().equals(Optional.of(true)),"negative interrogative semantics");

        var contradictory=new FrenchQuestionDialogue();
        contradictory.declare("document","A");
        contradictory.teach("Le document A est valide.");
        contradictory.teach("Le document A n'est pas valide.");
        var noAnswer=contradictory.ask("Le document A est-il valide ?");
        check(noAnswer.report().status()==IntegratedQuestionCycle.Status.INCONSISTENT,"no explosive repair");

        var independent=new FrenchQuestionDialogue();
        independent.declare("document","A");
        var noFacts=independent.ask("Le document A est-il validé ?");
        check(noFacts.report().status()==IntegratedQuestionCycle.Status.OPEN,"no fabricated fact");

        return n;
    }
    public static void main(String[] args) { System.out.println("FRENCH SELF-TEST OK - "+runAll()+" assertions"); }
}
