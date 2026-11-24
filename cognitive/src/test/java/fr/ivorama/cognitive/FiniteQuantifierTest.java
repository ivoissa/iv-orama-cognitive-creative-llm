package fr.ivorama.cognitive;

import java.util.*;

/** IVOC-LING-009 - three public question/answer cases plus adversarial regression. */
public final class FiniteQuantifierTest {
    private static int checks=0;
    private static void check(boolean b,String s) { checks++;if(!b)throw new AssertionError(s); }
    private static void rejects(Runnable f,String s) {
        boolean thrown=false;
        try {f.run();} catch(IllegalArgumentException|IllegalStateException ex){thrown=true;}
        check(thrown,s);
    }
    private static Optional<Boolean> answer(FrenchQuestionDialogue d,String q) {
        var reply=d.ask(q);
        check(!reply.pending(),"Question unexpectedly pending: "+q+" | "+reply.text());
        return reply.report().answer();
    }
    public static void main(String[] args) {
        FrenchQuestionDialogue none=new FrenchQuestionDialogue();
        none.declare("document","a");none.declare("document","b");
        none.teach("Aucun document n'est archivé.");
        Optional<Boolean> a=answer(none,"Le document a est-il archivé ?");
        check(a.equals(Optional.of(false)),"CASE 1: expected NO, got "+a);
        check(none.language().frozenKinds().contains("document"),"negative rule freezes domain");
        rejects(()->none.declare("document","c"),"no new member after finite quantification");
        check(answer(none,"Le document b est-il archivé ?").equals(Optional.of(false)),
            "negative universal instantiated for all members");
        rejects(()->none.teach("Aucun document n'est pas archivé."),"double negation unsupported");
        rejects(()->none.teach("Aucune document n'est archivé."),"gender incorrect");

        FrenchQuestionDialogue some=new FrenchQuestionDialogue();
        some.declare("document","a");some.declare("document","b");
        some.teach("Certains documents sont archivés.");
        Optional<Boolean> b0=answer(some,"Le document a est-il archivé ?");
        check(b0.isEmpty(),"CASE 2: existential cannot identify its witness, got "+b0);
        some.teach("Le document b n'est pas archivé.");
        Optional<Boolean> b1=answer(some,"Le document a est-il archivé ?");
        check(b1.equals(Optional.of(true)),"CASE 2B: refuted other witness implies first, got "+b1);
        check(answer(some,"Le document b est-il archivé ?").equals(Optional.of(false)),
            "negative witness remains refuted");
        rejects(()->some.declare("document","c"),"existential freezes declared domain");
        rejects(()->new FiniteQuantifierFrench(new ControlledFrench()).parse("Certains documents sont archivés."),
            "empty domain not accepted");

        FrenchQuestionDialogue except=new FrenchQuestionDialogue();
        except.declare("document","a");except.declare("document","b");
        except.teach("Tous les documents sont complets sauf le document b.");
        Optional<Boolean> c0=answer(except,"Le document b est-il complet ?");
        check(c0.isEmpty(),"CASE 3: exception does not imply NO, got "+c0);
        Optional<Boolean> c1=answer(except,"Le document a est-il complet ?");
        check(c1.equals(Optional.of(true)),"CASE 3B: non-excluded member YES, got "+c1);
        rejects(()->except.declare("document","c"),"exception freezes domain");
        except.teach("Le document b est complet.");
        check(answer(except,"Le document b est-il complet ?").equals(Optional.of(true)),
            "exception must not conflict with separately taught fact");

        FrenchQuestionDialogue variants=new FrenchQuestionDialogue();
        variants.declare("porte","a");variants.declare("porte","b");
        variants.teach("Aucune porte n'est ouverte.");
        check(answer(variants,"La porte a est-elle ouverte ?").equals(Optional.of(false)),"female none");
        FrenchQuestionDialogue e2=new FrenchQuestionDialogue();
        e2.declare("document","a");e2.declare("document","b");
        e2.teach("Tous les documents sauf le document a sont archivés.");
        check(answer(e2,"Le document a est-il archivé ?").isEmpty(),"before-exception still unknown");
        check(answer(e2,"Le document b est-il archivé ?").equals(Optional.of(true)),"before-exception positive");
        rejects(()->e2.teach("Tous les documents sauf la document a sont complets."),"reject invalid exception article");
        FrenchQuestionDialogue many=new FrenchQuestionDialogue();
        many.declare("document","a");many.declare("document","b");many.declare("document","c");
        rejects(()->many.teach("Certains documents sont archivés."),
            "existential on three members is explicitly outside this 2-SAT fragment");
        check(many.language().frozenKinds().isEmpty(),"failed interpretation does not freeze the domain");
        many.declare("document","d"); // rejection did not change the world

        FrenchQuestionDialogue inconsistent=new FrenchQuestionDialogue();
        inconsistent.declare("document","a");
        inconsistent.teach("Aucun document n'est archivé.");
        inconsistent.teach("Le document a est archivé.");
        var conflict=inconsistent.ask("Le document a est-il archivé ?");
        check(conflict.report().status()==IntegratedQuestionCycle.Status.INCONSISTENT,
            "explicit contradictory statements cannot yield a justified YES/NO");
        // Same answer must not be invented from partial knowledge.
        FrenchQuestionDialogue plain=new FrenchQuestionDialogue();
        plain.declare("document","a");plain.declare("document","b");
        check(answer(plain,"Le document a est-il archivé ?").isEmpty(),"no closed world by default");
        System.out.println("FINITE QUANTIFIER TEST OK "+checks+" checks");
        System.out.println("Q1 Le document a est-il archivé ? (Aucun document n'est archivé.) -> "+a);
        System.out.println("Q2 Le document a est-il archivé ? (Certains documents sont archivés.) -> "+b0+"; après non(b): "+b1);
        System.out.println("Q3 Le document b est-il complet ? (Tous sauf b.) -> "+c0+"; document a -> "+c1);
    }
}
