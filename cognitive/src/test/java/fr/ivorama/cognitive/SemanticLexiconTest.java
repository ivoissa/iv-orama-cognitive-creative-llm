package fr.ivorama.cognitive;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** IVOC-LING-002 : développement, séparé de l'évaluation EVAL-001. */
public final class SemanticLexiconTest {
    private SemanticLexiconTest() {}
    private static int count;
    private static void ok(boolean b,String why) { count++; if(!b) throw new AssertionError("LING-002: "+why); }
    private static void reject(Runnable action,String why) {
        boolean failed=false;
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException ex) { failed=true; }
        ok(failed,why);
    }
    public static int runAll() {
        count=0;
        SemanticLexicon lex=new SemanticLexicon();
        ok(lex.resolve("validé","document",SemanticLexicon.Number.SINGULAR).equals("approuve"),"builtin masculine");
        ok(lex.resolve("validée","porte",SemanticLexicon.Number.SINGULAR).equals("approuve"),"builtin feminine");
        ok(lex.resolve("validés","document",SemanticLexicon.Number.PLURAL).equals("approuve"),"builtin plural masculine");
        ok(lex.resolve("validées","porte",SemanticLexicon.Number.PLURAL).equals("approuve"),"builtin plural feminine");
        ok(lex.resolve("approuvés","document",SemanticLexicon.Number.PLURAL).equals("approuve"),"synonymous lemma");
        ok(lex.resolve("valide","document",SemanticLexicon.Number.SINGULAR).equals("valide"),"valide distinct from approuve");
        reject(() -> lex.resolve("validée","document",SemanticLexicon.Number.SINGULAR),"invalid agreement rejected");
        reject(() -> lex.resolve("validés","document",SemanticLexicon.Number.SINGULAR),"plural refused as singular");
        reject(() -> lex.resolve("inconnu","document",SemanticLexicon.Number.SINGULAR),"unknown word rejected");
        reject(() -> lex.register("INVALID ID",Set.of("document"),"x","x","xs","xs"),"semantic id validated");
        reject(() -> lex.register("propriete",Set.of("astronaute"),"x","x","xs","xs"),"noun kinds validated");
        int baseline=lex.entries().size();
        long v0=lex.version();
        lex.register("approuve",Set.of("document","porte"),"homologué","homologuée","homologués","homologuées");
        ok(lex.entries().size()==baseline+1,"new lexeme registered");
        ok(lex.version()==v0+1,"lexicon version advances on new meaning");
        lex.register("approuve",Set.of("document","porte"),"homologué","homologuée","homologués","homologuées");
        ok(lex.version()==v0+1,"identical lexical registration is idempotent");
        ok(lex.resolve("homologué","document",SemanticLexicon.Number.SINGULAR).equals("approuve"),"new synonym");
        ok(lex.resolve("homologuée","porte",SemanticLexicon.Number.SINGULAR).equals("approuve"),"new feminine");
        reject(() -> lex.resolve("homologué","dossier",SemanticLexicon.Number.SINGULAR),"restricted kinds");
        ok(lex.surfaceFor("homologué","porte",SemanticLexicon.Number.SINGULAR).equals("validée")
                || lex.surfaceFor("homologué","porte",SemanticLexicon.Number.SINGULAR).equals("approuvée"),
                "gendered generation grounded in meaning");
        ok(lex.surfaceFor("approuve","document",SemanticLexicon.Number.SINGULAR).equals("validé"),"canonical generation stable");
        lex.register("disponible",Set.of("document"),"libre","libre","libres","libres");
        lex.register("ouvert",Set.of("porte"),"libre","libre","libres","libres");
        ok(lex.resolve("libre","document",SemanticLexicon.Number.SINGULAR).equals("disponible"),"sortal disambiguation document");
        ok(lex.resolve("libre","porte",SemanticLexicon.Number.SINGULAR).equals("ouvert"),"sortal disambiguation porte");
        lex.registerPolysemy("disponible",Set.of("porte"),"libre","libre","libres","libres");
        try {
            lex.resolve("libre","porte",SemanticLexicon.Number.SINGULAR);
            throw new AssertionError("ambiguity not detected");
        } catch(SemanticLexicon.AmbiguousMeaningException expected) {
            ok(expected.candidates().equals(List.of("disponible","ouvert")),"both candidates preserved explicitly");
        }
        ControlledFrench fr=new ControlledFrench();
        fr.declareEntity("document","A"); fr.declareEntity("porte","B");
        fr.lexicon().register("approuve",Set.of("document","porte"),
                "homologué","homologuée","homologués","homologuées");
        var original=fr.interpret("Le document A est validé.");
        var synonym=fr.interpret("Le document A est homologué.");
        ok(original.formulas().equals(synonym.formulas()),"paraphrase same formula");
        ok(fr.interpret("La porte B est homologuée.").formulas().get(0)
                .equals(Formula.atom("approuve_porte_b")),"gendered new word parsed");
        ok(fr.interpret("La porte B est-elle homologuée ?").act()==ControlledFrench.Act.ASK,"question for new word");
        ok(fr.questionFor("porte","B","homologué").contains("validée"),"generated question corrected gender");
        var composite=CompositionalFrench.analyse(fr,"Le document A est homologué et archivé.");
        ok(composite.tree().formula().equals(Formula.and(Formula.atom("approuve_document_a"),
                Formula.atom("archive_document_a"))),"compositional tree uses lexical senses");
        var universal=fr.interpret("Tous les documents homologués sont archivés.");
        ok(universal.formulas().size()==1 && universal.formulas().get(0)
                .equals(Formula.implies(Formula.atom("approuve_document_a"),Formula.atom("archive_document_a"))),
                "universal grounded with lexical meaning");
        ok(fr.frozenKinds().isEmpty(),"reading lexemes and parsing universals does not freeze domain");
        fr.lexicon().register("disponible",Set.of("porte"),"libre","libre","libres","libres");
        fr.lexicon().registerPolysemy("ouvert",Set.of("porte"),"libre","libre","libres","libres");
        try {
            fr.interpret("La porte B est libre.");
            throw new AssertionError("ambiguous expression accepted");
        } catch(SemanticLexicon.AmbiguousMeaningException ex) {
            ok(ex.candidates().size()==2,"fr parser surfaces ambiguity, no automatic guess");
        }
        var dialogue=new FrenchQuestionDialogue();
        dialogue.declare("document","A");
        dialogue.registerAdjective("approuve",Set.of("document"),"homologué","homologuée","homologués","homologuées");
        int before=dialogue.cycle().premises().size();
        ok(before==0,"new lexical entries do not create factual premises");
        String factId=dialogue.teach("Le document A est homologué.").get(0);
        ok(dialogue.cycle().premises().size()==1,"facts admitted only through teach");
        ok(dialogue.cycle().premises().get(0).source().contains("lexique sha256="),
                "source records lexical interpretation version");
        var reply=dialogue.ask("Le document A est-il validé ?");
        ok(reply.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"synonym question answered");
        ok(reply.report().answer().equals(Optional.of(true)),"synonymous query true");
        dialogue.retract(factId);
        ok(dialogue.status(reply.questionId()).report().reopened(),"retraction still reopens Q");
        dialogue.registerAdjective("reserve",Set.of("document"),"libre","libre","libres","libres");
        dialogue.registerAmbiguousAdjective("disponible",Set.of("document"),"libre","libre","libres","libres");
        int qCount=dialogue.cycle().questions().size(), pCount=dialogue.cycle().premises().size();
        var ambiguous=dialogue.ask("Le document A est-il libre ?");
        ok(ambiguous.pending() && ambiguous.questionId().equals("Q2"),"one pending FCI created for ambiguous Q");
        reject(()->dialogue.teach("Le document A est libre."),"ambiguous assertion refused");
        ok(dialogue.cycle().questionActCount()==qCount+1,"ambiguous Q creates exactly one obligation");
        ok(dialogue.cycle().questions().size()==qCount,"unresolved question has no invented formula");
        ok(dialogue.cycle().premises().size()==pCount,"ambiguous parse never invents fact");
        String commands="entite document A\nlexeme approuve homologué homologuée homologués homologuées document\n"
                +"sais Le document A est homologué.\n? Le document A est-il validé ?\nquitter\n";
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        FrenchConsole.run(new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output,true,StandardCharsets.UTF_8));
        String console=output.toString(StandardCharsets.UTF_8);
        ok(console.contains("Sens lexical enregistré"),"interactive lexical registration");
        ok(console.contains("sous réserve des informations déclarées"),"interactive synonym used to answer Q");
        ok(!console.contains("Non interprété"),"interactive CLI no errors");
        return count;
    }
    public static void main(String[] args) { System.out.println("LEXICON TEST OK "+runAll()); }
}
