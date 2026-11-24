package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tests de développement de LING-003 ; distincts du banc indépendant des 100 questions. */
public final class LinguisticClarificationTest {
    private static int n;
    private static void check(boolean condition,String message) {
        n++;
        if(!condition) throw new AssertionError("LING-003: "+message);
    }
    private static void reject(Runnable action,String why) {
        boolean thrown=false;
        try {action.run();} catch(IllegalArgumentException|IllegalStateException ex) {thrown=true;}
        check(thrown,why);
    }
    private static void ambiguity(FrenchQuestionDialogue d, String concept, String word) {
        d.registerAmbiguousAdjective(concept,Set.of("document"),word,word,word+"s",word+"s");
    }
    public static int runAll() {
        n=0;
        var d=new FrenchQuestionDialogue();
        d.declare("document","A");
        d.teach("Le document A est disponible.");
        d.registerAdjective("reserve",Set.of("document"),"libre","libre","libres","libres");
        ambiguity(d,"disponible","libre");
        String lexicalBefore=d.lexicalFingerprint();
        int premiseBefore=d.cycle().premises().size();
        int eventBefore=d.cycle().journal().size();
        var open=d.ask("Le document A est-il libre ?");
        check(open.pending(),"ambiguous input is pending, not answered");
        check(open.reportOptional().isEmpty(),"no fictitious formula/report for ambiguous reading");
        check(d.cycle().questionActCount()==1,"exactly one Q act");
        check(d.cycle().unresolvedQuestions().size()==1,"pending Q stored in cycle");
        check(d.cycle().journal().size()==eventBefore+1,"single QUESTION_POSED event");
        check(d.cycle().journal().get(eventBefore).operation().equals("QUESTION_POSED"),"Q created by user's act");
        check(open.text().contains("reserve") && open.text().contains("disponible"),"all senses exposed");
        check(d.status(open.questionId()).pending(),"pending can be consulted by same ID");
        reject(()->d.clarify(open.questionId(),"ferme"),"wrong candidate rejected");
        check(d.cycle().questionActCount()==1 && d.cycle().premises().size()==premiseBefore,
                "invalid clarification does not create a question or fact");
        var closed=d.clarify(open.questionId(),"disponible");
        check(closed.questionId().equals(open.questionId()),"same question ID after clarification");
        check(!closed.pending() && closed.report().answer().equals(Optional.of(true)),"answer after chosen reading");
        check(closed.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "unverified fact stays conditional");
        check(d.cycle().questionActCount()==1 && d.cycle().unresolvedQuestions().isEmpty(),
                "clarification refines original Q, no new gap");
        check(d.cycle().journal().stream().filter(e->e.operation().equals("QUESTION_POSED")).count()==1,
                "exactly one Q act remains in audit log");
        check(d.cycle().journal().stream().anyMatch(e->e.operation().equals("QUESTION_CLARIFIED")),
                "clarification audited separately");
        check(d.lexicalFingerprint().equals(lexicalBefore),"lexicon immutable under clarification");
        check(d.cycle().premises().size()==premiseBefore,"clarification does not add a factual premise");
        check(d.status(closed.questionId()).report().question().equals(Formula.atom("disponible_document_a")),
                "resolved Q semantics frozen");
        String pId=d.cycle().premises().get(0).id();
        d.retract(pId);
        check(d.status(closed.questionId()).report().status()==IntegratedQuestionCycle.Status.OPEN,
                "removing source reopens same Q");
        check(d.status(closed.questionId()).report().reopened(),"reopening explicit");
        var unknown=d.ask("Le document A est-il libre ?");
        var other=d.clarify(unknown.questionId(),"reserve");
        check(other.report().status()==IntegratedQuestionCycle.Status.OPEN,
                "different chosen sense does not borrow evidence for other sense");
        check(d.status(closed.questionId()).report().question().equals(Formula.atom("disponible_document_a")),
                "later choices don't re-interpret previous question");
        // Deux termes ambigus, mais UN acte Q même après deux clarifications.
        var multi=new FrenchQuestionDialogue();
        multi.declare("document","A");
        ambiguity(multi,"reserve","libre");
        ambiguity(multi,"disponible","libre");
        ambiguity(multi,"valide","stable");
        ambiguity(multi,"actif","stable");
        var first=multi.ask("Le document A est-il libre et stable ?");
        check(first.pending(),"multiple ambiguous words admitted as one pending Q");
        var second=multi.clarify(first.questionId(),"reserve");
        check(second.pending() && second.questionId().equals(first.questionId()),"second sense prompt, same id");
        check(multi.cycle().questionActCount()==1,"no second Q act during multiword clarification");
        var last=multi.clarify(first.questionId(),"actif");
        check(!last.pending() && last.questionId().equals(first.questionId()),"two clarifications resolve one Q");
        check(multi.cycle().journal().stream().filter(e->e.operation().equals("QUESTION_POSED")).count()==1,
                "one Q event for several lexical decisions");
        check(last.report().question().equals(Formula.and(Formula.atom("reserve_document_a"),
                Formula.atom("actif_document_a"))),"scoped composition retains both selected senses");
        reject(()->multi.clarify(first.questionId(),"reserve"),"already disambiguated Q rejects new clarification");
        reject(()->multi.ask("Le document A est libre."),"assertion without interrogation rejected");
        // Réelle console : user supplies a lexical choice, receives answer.
        String script="entite document A\nsais Le document A est disponible.\n"
            +"lexeme reserve libre libre libres libres document\n"
            +"lexeme-ambigu disponible libre libre libres libres document\n"
            +"? Le document A est-il libre ?\nprecise Q1 disponible\nquestions\nquitter\n";
        var captured=new ByteArrayOutputStream();
        FrenchConsole.run(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(captured,true,StandardCharsets.UTF_8));
        String output=captured.toString(StandardCharsets.UTF_8);
        check(output.contains("Clarification nécessaire") && output.contains("precise Q1"),"console asks for clarification");
        check(output.contains("Oui, sous réserve"),"console answers after clarification");
        check(!output.contains("Non interprété"),"normal clarification script no errors");
        return n;
    }
    public static void main(String[] args) { System.out.println("LING-003 OK - "+runAll()); }
}
