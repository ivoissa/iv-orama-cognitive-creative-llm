package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Revue Gemini/Claude du lexique v0.5.3 : tests de développement, PAS benchmark indépendant. */
public final class LexicalReviewTest {
    private static int checks;
    private static void check(boolean b, String reason) {
        checks++; if(!b) throw new AssertionError("REV-LING-003: "+reason);
    }
    private static void reject(Runnable a,String reason) {
        boolean thrown=false;
        try { a.run(); } catch(IllegalArgumentException|IllegalStateException ex) { thrown=true; }
        check(thrown,reason);
    }
    private static void addHomologue(SemanticLexicon lex) {
        lex.register("approuve",Set.of("document","porte"),
                "homologué","homologuée","homologués","homologuées");
    }
    public static int runAll() {
        checks=0;
        var lex=new SemanticLexicon();
        String original=lex.fingerprint();
        check(original.matches("[0-9a-f]{64}"),"SHA-256 fixed length");
        long before=lex.version();
        reject(()->lex.register("approuve",Set.of("porte"),
                "fermé","fermée","fermés","fermées"),
                "unannounced semantic collision refused");
        check(before==lex.version() && original.equals(lex.fingerprint()),
                "failed registration is atomic");
        addHomologue(lex);
        String added=lex.fingerprint();
        check(!original.equals(added),"new entry changes content hash");
        addHomologue(lex);
        check(added.equals(lex.fingerprint()),"idempotent re-add doesn't alter content");
        check("approuve".equals(lex.resolve("homologuée","porte",SemanticLexicon.Number.SINGULAR)),
                "female adjective interpreted");
        reject(()->lex.resolve("homologué","porte",SemanticLexicon.Number.SINGULAR),
                "wrong gender refused");
        reject(()->lex.resolve("homologués","porte",SemanticLexicon.Number.PLURAL),
                "female plural agreement enforced");
        reject(()->lex.resolve("homologuées","document",SemanticLexicon.Number.PLURAL),
                "male plural agreement enforced");
        lex.registerPolysemy("disponible",Set.of("porte"),
                "fermée","fermée","fermées","fermées");
        try {
            lex.resolve("fermée","porte",SemanticLexicon.Number.SINGULAR);
            throw new AssertionError("Expected explicit lexical ambiguity");
        } catch(SemanticLexicon.AmbiguousMeaningException ex) {
            check(ex.candidates().containsAll(List.of("disponible","ferme")),
                    "intentional ambiguity keeps both meanings");
        }
        Path file=null;
        try {
            file=Files.createTempFile("ivoc-lexicon-review",".txt");
            lex.save(file);
            var restored=new SemanticLexicon();
            long rv=restored.version();
            restored.load(file);
            check(rv+1==restored.version(),"load is an explicit versioned edit");
            check(lex.fingerprint().equals(restored.fingerprint()),"round-trip preserves same hash");
            check(restored.resolve("homologué","document",SemanticLexicon.Number.SINGULAR).equals("approuve"),
                    "round-trip preserves lexical semantics");
            check(!Files.readString(file).contains("java.io"),"human-readable serialization");
            String content=Files.readString(file);
            Files.writeString(file,content.replace("homologué","fabriqué"),StandardCharsets.UTF_8);
            String protectedHash=restored.fingerprint();
            try { restored.load(file); throw new AssertionError("Expected invalid SHA-256"); }
            catch(IOException expected) { check(protectedHash.equals(restored.fingerprint()),
                    "tampered file rejected without partial mutation"); }
        } catch(IOException ex) { throw new AssertionError("Lexicon I/O test",ex); }
        finally { if(file!=null) try { Files.deleteIfExists(file); } catch(IOException ignored) {} }

        var dialogue=new FrenchQuestionDialogue();
        dialogue.declare("document","A");
        String premise=dialogue.teach("Le document A est validé.").get(0);
        var question=dialogue.ask("Le document A est-il validé ?");
        var stored=question.report().question();
        long knowledge=dialogue.cycle().premises().size();
        long events=dialogue.cycle().journal().size();
        int obligations=dialogue.cycle().questions().size();
        long lexicalVersion=dialogue.language().lexicon().version();
        dialogue.registerAdjective("approuve",Set.of("document"),
                "homologué","homologuée","homologués","homologuées");
        check(dialogue.language().lexicon().version()>lexicalVersion,"new lexicon revision");
        check(dialogue.cycle().premises().size()==knowledge,"lexical edit creates no premise");
        check(dialogue.cycle().journal().size()==events,"lexical edit doesn't create FCI event");
        check(dialogue.cycle().questions().size()==obligations,"lexical edit doesn't create question");
        check(dialogue.status(question.questionId()).report().question().equals(stored),
                "existing Q stores canonical meaning, not reparsed surface");
        check(dialogue.status(question.questionId()).report().answer().equals(Optional.of(true)),
                "existing established answer unchanged by new alias");
        check(dialogue.ask("Le document A est-il homologué ?").report().answer().equals(Optional.of(true)),
                "new alias can express old knowledge");
        check(dialogue.cycle().premises().get(0).id().equals(premise)
                && dialogue.cycle().premises().get(0).source().contains("sha256="),
                "original premise has lexical content fingerprint");
        reject(()->dialogue.registerAdjective("ferme",Set.of("document"),
                "validé","validée","validés","validées"),
                "later alias cannot silently steal earlier meaning");
        check(dialogue.cycle().questions().size()==obligations+1,
                "rejected alias did not create additional Q");
        for(String phrase : List.of("Le document A est validé.","Le document A est archivé.",
                "Le document A est-il approuvé ?","Le document A n'est pas validé.")) {
            var base=dialogue.language().interpret(phrase);
            var composed=CompositionalFrench.analyse(dialogue.language(),phrase).interpretation();
            check(base.act()==composed.act() && base.formulas().equals(composed.formulas()),
                    "old and new parsers agree on simple phrase "+phrase);
        }
        var wrong=dialogue.ask("Le document A est-il validée ?");
        check(wrong.pending() && dialogue.cycle().questionAct(wrong.questionId()).state()==
                IntegratedQuestionCycle.ActState.UNINTERPRETED,"bad agreement is not interpreted as a true/false proposition");
        var wrongCompound=dialogue.ask("Le document A est-il ouvert et homologuée ?");
        check(wrongCompound.pending() && dialogue.cycle().questionAct(wrongCompound.questionId()).state()==
                IntegratedQuestionCycle.ActState.UNINTERPRETED,"wrong compound agreement is uninterpreted");
        return checks;
    }
    public static void main(String[] args) { System.out.println("REVIEW-LING-003 OK - "+runAll()); }
}
