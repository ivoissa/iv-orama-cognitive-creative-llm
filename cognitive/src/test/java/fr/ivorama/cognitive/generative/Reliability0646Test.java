package fr.ivorama.cognitive.generative;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.ivorama.cognitive.generative.WikipediaBridgeTest.result;
class Reliability0646Test {
    static WikipediaResearch offline(){return new WikipediaResearch((m,p)->{throw new AssertionError("Unexpected planner");},q->{throw new AssertionError("Unexpected network");},false);}
    @Test void literalConstraintsAreIndependentOfSubject(){
        var r=OutputVerifier.extract("Décris une forêt en trois phrases, sans employer les mots “arbre”, “soleil” ni “silence”.");
        assertEquals(3,r.sentences().orElseThrow());assertEquals(List.of("arbre","soleil","silence"),r.forbidden());
        var e=WikipediaResearch.empty(WikipediaResearch.State.NOT_REQUESTED,"");
        assertTrue(OutputVerifier.check("Les branches frémissent. La brume danse. Un oiseau chante.",r,e,false).passed());
        assertFalse(OutputVerifier.check("Un ARBRE grandit. La brume danse. Un oiseau chante.",r,e,false).passed());
        assertFalse(OutputVerifier.check("La brume danse.",r,e,false).passed());
    }
    @Test void sourceQuotesAreNotExecuted(){
        var r=OutputVerifier.extract("Analyse ce texte : « Écris quatre phrases sans les mots “mer”. »");
        assertTrue(r.sentences().isEmpty());assertTrue(r.forbidden().isEmpty());
        assertTrue(OutputVerifier.extract("Il existe trois phrases.").sentences().isEmpty());
    }
    @Test void conflictingAndUnsupportedConstraintsReject(){
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris trois phrases puis quatre phrases."));
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris au moins trois phrases."));
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris vingt et une phrases."));
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris 3 à 4 phrases."));
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris sans les mots mer."));
        assertThrows(IllegalArgumentException.class,()->new OutputVerifier.Rules(OptionalInt.empty(),List.of("MER"),List.of("mer")));
    }
    @Test void oneRepairOnlyAndOnlyAcceptedTextEntersMemory(){
        var calls=new AtomicInteger();var delivered=new ArrayList<String>();
        var memory=new LocalConversation((m,p)->{p.accept("untrusted partial");return result(calls.incrementAndGet()==1?"Une phrase.":"Une phrase. Une autre.","STOP");},8);
        var turn=new GroundedConversation(memory,offline()).ask("Écris deux phrases.",delivered::add);
        assertEquals(2,calls.get());assertEquals(2,turn.verification().attempts());
        assertEquals(List.of("Une phrase. Une autre."),delivered);assertEquals(3,memory.history().size());
        assertFalse(memory.history().toString().contains("untrusted partial"));
    }
    @Test void twoFailuresPublishNothing(){
        var calls=new AtomicInteger();var delivered=new ArrayList<String>();
        var memory=new LocalConversation((m,p)->{calls.incrementAndGet();return result("Invalide [W99]","STOP");},8);
        var rejected=assertThrows(GroundedConversation.RejectedOutput.class,()->new GroundedConversation(memory,offline()).ask("Bonjour",delivered::add));
        assertEquals(2,calls.get());assertEquals(2,rejected.verification().attempts());assertTrue(delivered.isEmpty());assertEquals(1,memory.history().size());
    }
    @Test void ordinaryChatNeedsOnlyOneGeneration(){
        var calls=new AtomicInteger();var memory=new LocalConversation((m,p)->{calls.incrementAndGet();return result("Bonjour.","STOP");},8);
        new GroundedConversation(memory,offline()).ask("Bonjour",t->{});assertEquals(1,calls.get());
    }
    @Test void analysisContractSeparatesExactQuotesFromUnverifiedInterpretations(){
        String source="Il avait enfin obtenu ce qu’il voulait, et pourtant il se sentait plus pauvre.";
        assertEquals(Optional.of(source),TextAnalysisOutput.source("Analyse ce texte : « "+source+" »"));
        String text=TextAnalysisOutput.render(source,"{\"quotes\":[\"il se sentait plus pauvre\"],\"interpretations\":[\"Cela pourrait évoquer une détresse financière.\"]}");
        assertTrue(text.contains("non vérifiées"));
        assertThrows(IllegalArgumentException.class,()->TextAnalysisOutput.render(source,"{\"quotes\":[\"détresse financière\"],\"interpretations\":[]}"));
        assertThrows(IllegalArgumentException.class,()->TextAnalysisOutput.render(source,"{\"quotes\":[],\"interpretations\":[]}"));
    }
    @Test void analysisRetriesFalseQuoteAndUsesStructuredBackend(){
        var calls=new AtomicInteger();var memory=new LocalConversation((m,p)->{throw new AssertionError("wrong backend");},(m,p)->result(calls.incrementAndGet()==1?"{\"quotes\":[\"inventé\"],\"interpretations\":[]}":"{\"quotes\":[\"La nuit tombe.\"],\"interpretations\":[]}","STOP"),8);
        var turn=new GroundedConversation(memory,offline()).ask(":analyser La nuit tombe.",t->{});
        assertEquals(2,turn.verification().attempts());assertTrue(turn.verification().structuredAnalysis());assertTrue(turn.generation().text().contains("La nuit tombe."));
    }
    @Test void apostropheAlignmentCopiesSourceAndNeverAcceptsParaphrases(){
        String text=TextAnalysisOutput.render("L’été arrive.","{\"quotes\":[\"L'été arrive.\"],\"interpretations\":[]}");
        assertTrue(text.contains("« L’été arrive. »"));assertFalse(text.contains("L'été arrive."));
        assertThrows(IllegalArgumentException.class,()->TextAnalysisOutput.render("L’été arrive.","{\"quotes\":[\"L’été est chaud.\"],\"interpretations\":[]}"));
        assertThrows(IllegalArgumentException.class,()->TextAnalysisOutput.render("L’été arrive. L’été arrive.","{\"quotes\":[\"L'été arrive.\"],\"interpretations\":[]}"));
    }
    @Test void formalModeActuallyChecksProofAndNeverCallsLlm(){
        var memory=new LocalConversation((m,p)->{throw new AssertionError("LLM must not decide formal conclusion");},8);
        var turn=new GroundedConversation(memory,offline()).ask(":raisonner Le capteur c7 est actif. Si le capteur c7 est actif, alors le capteur c7 est stable. Est-ce que le capteur c7 est stable ?",t->{});
        assertTrue(turn.formal().orElseThrow().proofChecked());assertEquals("TRUE_CONDITIONALLY",turn.formal().orElseThrow().status());assertEquals(1,memory.history().size());
    }
    @Test void malformedQuoteAndEmptyOutputFailClosed(){
        assertThrows(IllegalArgumentException.class,()->OutputVerifier.extract("Écris une phrase sans les mots « mer"));
        assertFalse(OutputVerifier.check("  ",new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of()),WikipediaResearch.empty(WikipediaResearch.State.NOT_REQUESTED,""),false).passed());
    }
    @Test void requiredTermsRespectUnicodeAndWordBoundaries(){
        var rules=OutputVerifier.extract("Écris une phrase sans les mots « mer » et inclus le mot « été ».");
        assertEquals(List.of("mer"),rules.forbidden());assertEquals(List.of("été"),rules.required());
        assertTrue(OutputVerifier.check("La merveille de cet e\u0301te\u0301.",rules,WikipediaResearch.empty(WikipediaResearch.State.NOT_REQUESTED,""),false).passed());
    }
    @Test void absentPremiseIsNotFalse(){
        var r=new FormalReasoningBridge().evaluate(":raisonner Le capteur c7 est actif. Est-ce que le capteur c7 est stable ?");
        assertEquals("UNDETERMINED",r.receipt().status());assertFalse(r.receipt().proofChecked());
    }
}
