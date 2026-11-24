package fr.ivorama.cognitive;

import java.util.*;

/** Tests de développement LING-001, SÉPARÉS du benchmark indépendant des 100 questions. */
public final class LinguisticCompositionTest {
    private LinguisticCompositionTest() {}
    private static int assertions;
    private static void ok(boolean value,String description) {
        assertions++; if(!value) throw new AssertionError("LING-001 : "+description);
    }
    private static void reject(Runnable r,String description) {
        boolean failed=false;
        try { r.run(); } catch (IllegalArgumentException|IllegalStateException e) {failed=true;}
        ok(failed,description);
    }
    public static int runAll() {
        assertions=0;
        ControlledFrench fr=new ControlledFrench();
        fr.declareEntity("document","A");
        fr.declareEntity("document","B");
        fr.declareEntity("porte","B");
        Formula approved=Formula.atom("approuve_document_a");
        Formula archived=Formula.atom("archive_document_a");
        var c=CompositionalFrench.analyse(fr,"Le document A est validé et archivé.");
        ok(c.act()==ControlledFrench.Act.ASSERT,"elliptical declarative detected");
        ok(c.tree().leaves()==2,"two leaf constituents");
        ok(c.tree() instanceof CompositionalFrench.Conjunction,"semantic conjunction AST");
        ok(c.tree().formula().equals(Formula.and(approved,archived)),"compositional truth conditions");
        ok(c.interpretation().formulas().size()==1,"one formula built from two leaves");
        var ci=CompositionalFrench.analyse(fr,"Le document A est-il validé et archivé ?");
        ok(ci.act()==ControlledFrench.Act.ASK,"inverted conjunctive question");
        ok(ci.tree().formula().equals(c.tree().formula()),"question and assertion share propositional meaning");
        var ce=CompositionalFrench.analyse(fr,"Est-ce que le document A est validé et archivé ?");
        ok(ce.act()==ControlledFrench.Act.ASK,"interrogative wrapper");
        ok(ce.tree().formula().equals(c.tree().formula()),"paraphrase invariant");
        var full=CompositionalFrench.analyse(fr,
                "Le document A est validé et la porte B n'est pas ouverte.");
        ok(full.tree().leaves()==2,"full clauses with independently scoped negation");
        ok(full.tree().formula().equals(Formula.and(approved,Formula.not(Formula.atom("ouvert_porte_b")))),
                "negative second clause remains negative");
        var fullQ=CompositionalFrench.analyse(fr,
                "Est-ce que le document A est validé et la porte B n'est pas ouverte ?");
        ok(fullQ.act()==ControlledFrench.Act.ASK,"full clauses question");
        ok(fullQ.tree().formula().equals(full.tree().formula()),"statement-to-question compositional roundtrip");
        reject(() -> CompositionalFrench.analyse(fr,"Le document A n'est pas validé et archivé."),
                "negation with elliptical coordination ambiguous");
        reject(() -> CompositionalFrench.analyse(fr,"Le document A est valide ou archivé et actif ?"),
                "mixed connector ambiguity must reject");
        reject(() -> CompositionalFrench.analyse(fr,"Le document A est validé et mystérieux ?"),
                "unknown lexeme cannot be hallucinated");
        reject(() -> CompositionalFrench.analyse(fr,"Le document A est validé et la porte B est ouvert ?"),
                "gender feature mismatch");
        reject(() -> fr.interpret("Le document A est validée."),"masculine adjective required");
        reject(() -> fr.interpret("Le document A est-elle validé ?"),"inversion mismatch on masculine subject");
        reject(() -> fr.interpret("La porte B est-il ouverte ?"),"inversion mismatch on feminine subject");
        reject(() -> CompositionalFrench.analyse(fr,"La porte B est-il ouverte et archivée ?"),
                "inversion mismatch in composed question");
        reject(() -> fr.interpret("La porte B est ouverte et archivée."),
                "legacy parser does not guess compositional structure");
        ok(CompositionalFrench.analyse(fr,"La porte B est ouverte et archivée.").tree().leaves()==2,
                "feminine property coordination");
        reject(() -> fr.interpret("La porte B est ouvert."),"feminine agreement rejected");
        reject(() -> fr.interpret("Pourquoi le document A est-il archivé ?"),"why cannot masquerade as yes/no");
        reject(() -> fr.interpret("Toutes les documents validés sont archivés."),"universal determiner gender");
        reject(() -> fr.interpret("Tous les documents validées sont archivés."),"universal adjective number/gender");
        reject(() -> fr.interpret("Toutes les portes ouvertes sont archivés."),"feminine universal adjective");
        ok(fr.interpret("Toutes les portes ouvertes sont archivées.").act()==ControlledFrench.Act.UNIVERSAL,
                "correct feminine plural quantification");
        ok(fr.interpret("Tous les documents validés sont archivés.").act()==ControlledFrench.Act.UNIVERSAL,
                "legacy masculine plural quantification unchanged");

        // End-to-end Q introduced once, conjunction must be completely determined.
        FrenchQuestionDialogue dialogue=new FrenchQuestionDialogue();
        dialogue.declare("document","A");
        dialogue.teach("Le document A est validé.");
        var unanswered=dialogue.ask("Est-ce que le document A est validé et archivé ?");
        ok(unanswered.report().status()==IntegratedQuestionCycle.Status.OPEN,"conjunctive question not yet repaired");
        ok(dialogue.cycle().questions().size()==1,"only explicit Ask creates Q");
        String id=dialogue.teach("Le document A est archivé.").get(0);
        var complete=dialogue.status(unanswered.questionId());
        ok(complete.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "composite Q conditionally repaired by second fact");
        ok(complete.report().answer().equals(Optional.of(true)),"conjunction true");
        ok(complete.report().proofChecked(),"conjunction carries a verified proof, not model-only");
        dialogue.retract(id);
        ok(dialogue.status(unanswered.questionId()).report().reopened(),"retraction reopens same Q");
        int count=dialogue.cycle().questions().size();
        var why=dialogue.ask("Pourquoi le document A est-il archivé ?");
        ok(why.questionId().startsWith("Q") && why.pending(),"why is a separate explanatory question, not yes/no");
        ok(dialogue.cycle().questionAct(why.questionId()).state()==IntegratedQuestionCycle.ActState.EXPLANATION,
                "explanatory act typed and persisted");
        ok(dialogue.cycle().questions().size()==count,"explanatory Q never masquerades as yes/no report");
        // No test examples above are part of the frozen, external 100-question benchmark.
        return assertions;
    }
    public static void main(String[] args) {
        System.out.println("LINGUISTIC SELF-TEST OK - "+runAll()+" assertions");
    }
}
