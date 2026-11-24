package fr.ivorama.cognitive.generative;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.langchain4j.data.message.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import fr.ivorama.cognitive.generation.*;

class Corrective073Test {
    static GenerationBackend.Result result(String text){return new GenerationBackend.Result(text,"FIXTURE","STOP",1,-1,10,10);}
    static GroundedConversation session(LocalConversation memory){return new GroundedConversation(memory,
            new WikipediaResearch((m,p)->{throw new AssertionError("No planning");},q->List.of(),false),null,false,GroundedConversation.Options.candidate073());}
    @Test void numberingIsNotAnExtraSentenceButActualPreambleStillCounts(){
        assertEquals(3,OutputVerifier.sentenceCount("Voici le récit :\n1. Le vent souffle.\n2. Une porte claque.\n3. La nuit tombe."));
        assertEquals(4,OutputVerifier.sentenceCount("Voici une introduction.\n1. Le vent souffle.\n2. Une porte claque.\n3. La nuit tombe."));
        assertEquals(2,OutputVerifier.sentenceCount("1.5 litre est disponible. Le reste manque."));
    }
    @Test void inheritedConstraintsAreEnforcedAndRejectedDraftNeverEntersHistory(){
        var count=new AtomicInteger();var seen=new ArrayList<List<ChatMessage>>();
        var memory=new LocalConversation((m,p)->{seen.add(List.copyOf(m));return result(switch(count.incrementAndGet()){
            case 1->"La mer monte. Le vent souffle. La nuit tombe.";
            case 2->"Un bateau arrive.";
            default->"La forêt bruisse. Une chouette veille. La lune paraît.";});},5);
        var s=session(memory);s.ask("Écris un récit de trois phrases sans le mot « bateau ».",t->{});
        var answer=s.ask("Réécris ce récit en conservant les mêmes contraintes et en changeant le lieu.",t->{});
        assertEquals(2,answer.verification().attempts());assertEquals(3,OutputVerifier.sentenceCount(answer.generation().text()));
        assertTrue(seen.get(1).stream().noneMatch(AiMessage.class::isInstance));
        assertTrue(seen.get(1).stream().anyMatch(m->m instanceof UserMessage u&&u.singleText().contains("USER_TASK_STATE")&&u.singleText().contains("bateau")));
        assertFalse(memory.history().stream().anyMatch(m->m instanceof AiMessage a&&a.text().equals("Un bateau arrive.")));
        assertTrue(answer.verification().report().checks().contains("FORBIDDEN_TERM"));
    }
    @Test void unrelatedNewTaskDoesNotInheritOldConstraints(){
        var count=new AtomicInteger();var s=session(new LocalConversation((m,p)->result(count.incrementAndGet()==1?"La mer monte. Le vent souffle. La nuit tombe.":"Un bateau arrive."),5));
        s.ask("Écris un récit de trois phrases sans le mot « bateau ».",t->{});
        assertEquals("Un bateau arrive.",s.ask("Écris une phrase avec le mot « bateau ».",t->{}).generation().text());
    }
    @Test void unchangedRewriteIsRejected(){
        var count=new AtomicInteger();var s=session(new LocalConversation((m,p)->result(count.incrementAndGet()<=2?"La mer monte. Le vent souffle. La nuit tombe.":"La forêt bruisse. Une chouette veille. La lune paraît."),5));
        s.ask("Écris un récit de trois phrases.",t->{});
        assertEquals(2,s.ask("Réécris ce récit en conservant les mêmes contraintes et en changeant le lieu.",t->{}).verification().attempts());
    }
    @Test void userReferentSurvivesFailedGenerationWithoutKeepingRejectedText(){
        var count=new AtomicInteger();var seen=new ArrayList<List<ChatMessage>>();
        var memory=new LocalConversation((m,p)->{seen.add(List.copyOf(m));return count.incrementAndGet()<=2?new GenerationBackend.Result("BROUILLON_REJETE","FIXTURE","LENGTH",1,-1,10,768):result("Un service reste accessible pendant une panne.");},5);
        var s=session(memory);assertThrows(GroundedConversation.RejectedOutput.class,()->s.ask("Explique la différence entre sauvegarde et disponibilité.",t->{}));
        assertEquals(1,memory.history().size());s.ask("Donne maintenant un exemple de la seconde notion.",t->{});
        assertTrue(seen.get(2).stream().anyMatch(m->m instanceof UserMessage u&&u.singleText().contains("\"referent\":\"disponibilité\"")));
        assertFalse(seen.get(2).toString().contains("BROUILLON_REJETE"));
    }
    @Test void memoryEvictsCompletePairsAtEvenAndOddLimits(){
        for(int limit:new int[]{4,5,12,17}){
            var memory=new LocalConversation((m,p)->result("Réponse."),limit);
            for(int i=0;i<20;i++)memory.ask("Question "+i,t->{});
            var messages=memory.history();assertTrue(messages.size()<=limit);assertInstanceOf(SystemMessage.class,messages.getFirst());
            for(int i=1;i<messages.size();i+=2){assertInstanceOf(UserMessage.class,messages.get(i));assertInstanceOf(AiMessage.class,messages.get(i+1));}
        }
    }
    @Test void incidentCannotPublishAnInventedObservation(){
        var count=new AtomicInteger();String source="Trois pods produisent plus de 500 connexions. Quelles mesures choisir avant une reprise ?";
        GenerationBackend structured=(m,p)->result(count.incrementAndGet()==1?
                "{\"observations\":[\"Le serveur est épuisé.\"],\"hypotheses\":[],\"checks\":[\"Mesurer les connexions.\"]}":
                "{\"hypotheses\":[\"Une saturation est possible.\"],\"checks\":[\"Mesurer par pod les connexions actives et les effets déjà produits.\"]}");
        var memory=new LocalConversation((m,p)->{throw new AssertionError("Structured incident expected");},structured,5);
        var answer=session(memory).ask(source,t->{});
        assertEquals(2,answer.verification().attempts());assertTrue(answer.generation().text().contains("Hypothèses non vérifiées"));
        assertFalse(answer.generation().text().contains("Le serveur est épuisé."));
    }
    @Test void quotedPayloadCannotInheritConstraintsOrBindReferents(){
        assertFalse(TaskState.inherits("Analyse « mêmes contraintes »."));
        var state=new TaskState();
        state.prepare("Analyse « la différence entre le ciel et la mer ».",new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of()));
        assertEquals("",state.context("Donne un exemple de la seconde notion.",new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of())));
    }
    @Test void quotedIncidentInstructionCannotActivateMode(){
        assertFalse(IncidentOutput.requested("Résume « diagnostique cet incident ».",RequestPlanner.legacy()));
    }
    @Test void canonicalJsonPreservesValuesArraysAndMultipleObjects(){
        String a="{\"z\":[2,1],\"a\":{\"b\":\"} et \\\"citation\\\"\",\"a\":1}}\n{\"y\":true,\"x\":null}";
        String b="{\"a\":{\"a\":1,\"b\":\"} et \\\"citation\\\"\"},\"z\":[2,1]}\n{\"x\":null,\"y\":true}";
        assertEquals(b,PromptJson.canonical(a));assertEquals(b,PromptJson.canonical(b));
        assertThrows(IllegalArgumentException.class,()->PromptJson.canonical("{\"a\":1,\"a\":2}"));
    }
    @Test void creativePolicyHasNoIncidentInstructions(){
        var c=RequestContract.capture("Écris un récit.",OptionalInt.empty(),List.of(),List.of(),Optional.empty());
        String policy=GenerationPolicy.instructions(c,false);assertFalse(policy.contains("idempotence"));assertTrue(policy.contains("brièvement"));
    }
    @Test void repairSelectionAvoidsEnvelopeOnShortAnswers(){
        assertFalse(TextRepair.preferRepair("Une petite phrase. ".repeat(20)+"dragon",List.of("FORBIDDEN_TERM: dragon"),false));
        assertTrue(TextRepair.preferRepair("Une longue histoire. ".repeat(100)+"dragon",List.of("FORBIDDEN_TERM: dragon"),false));
    }
    @Test void aggregateBudgetBoundsLaterCallsAndUnknownUsage(){
        try(var budget=new TurnBudget(1024,Duration.ofSeconds(30))){
            var a=budget.allocate(768,Duration.ofSeconds(60));assertEquals(768,a.outputTokens());assertTrue(a.timeout().compareTo(Duration.ofSeconds(30))<=0);
            budget.consumed(a,768);var b=budget.allocate(768,Duration.ofSeconds(60));assertEquals(256,b.outputTokens());
            budget.consumed(b,null);assertThrows(IllegalStateException.class,()->budget.allocate(768,Duration.ofSeconds(1)));
        }
        assertNull(TurnBudget.current());
    }
}
