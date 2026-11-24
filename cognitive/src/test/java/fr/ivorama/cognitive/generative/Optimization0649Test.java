package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.WebSource;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic contracts, no real Qwen or real remote API. */
class Optimization0649Test {
    @Test void trimsCompleteHistoricalPairsAndPreservesProtectedItems(){
        // system, old user, old assistant, recent user, recent assistant, evidence, current user
        assertEquals(List.of(0,3,4,5,6),PromptBudgetPolicy.select(
                List.of(30,300,300,100,100,500,100),2,900));
        assertEquals(List.of(0,1,2,3,4,5,6),PromptBudgetPolicy.select(
                List.of(30,300,300,100,100,500,100),2,1500));
        assertThrows(IllegalArgumentException.class,()->PromptBudgetPolicy.select(
                List.of(400,100,100,600,500),1,1000));
    }

    @Test void contextAdmissionNeverSendsAnOversizeCurrentRequest(){
        var cfg=new LocalLlmConfig(URI.create("http://127.0.0.1:11434"),"test:local",1024,128,2,5,Duration.ofSeconds(5),0.1);
        var over=List.<ChatMessage>of(SystemMessage.from("Cadre"),UserMessage.from("X".repeat(2500)));
        assertThrows(IllegalArgumentException.class,()->PromptBudget.prepare(over,cfg));
        var text=List.<ChatMessage>of(SystemMessage.from("Cadre"),UserMessage.from("1".repeat(900)),AiMessage.from("R".repeat(900)),UserMessage.from("Réponds"));
        var selected=PromptBudget.prepare(text,cfg);
        assertEquals(2,selected.size());assertSame(text.getFirst(),selected.getFirst());assertSame(text.getLast(),selected.getLast());
    }

    @Test void oneSchemaRetryKeepsValidationAndOriginalQuestion(){
        var calls=new AtomicInteger();var received=new ArrayList<List<ChatMessage>>();
        GenerationBackend backend=(messages,partial)->{
            received.add(List.copyOf(messages));
            String output=calls.incrementAndGet()==1?"{}":"{\"tasks\":[\"EXPLANATION\"],\"excerpts\":[],\"missingInformation\":[],\"researchNeed\":\"NONE\",\"terms\":\"\"}";
            return new GenerationBackend.Result(output,"fixture","STOP",1,1,1,1);
        };
        var interpretation=new RequestPlanner(backend).interpret("Explique la gravité",List.of());
        assertEquals(2,calls.get());assertTrue(interpretation.usable());
        assertEquals("Explique la gravité",((UserMessage)received.get(1).get(1)).singleText());
        // Inspect actual contents; SDK debug strings contain empty attributes = {}.
        assertTrue(received.get(1).stream().noneMatch(AiMessage.class::isInstance));
        assertFalse(received.get(1).stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).anyMatch(message -> message.singleText().contains("{}")));
    }

    @Test void documentaryRetryNeverUsesInventedTerms(){
        var calls=new AtomicInteger();var network=new AtomicInteger();
        GenerationBackend proposal=(messages,partial)->new GenerationBackend.Result(
                calls.incrementAndGet()==1?"{\"terms\":\"terme inventé\"}":"{\"terms\":\"gravité\"}","fixture","STOP",1,1,1,1);
        var research=new WikipediaResearch(proposal,term->{network.incrementAndGet();return List.of();},true);
        assertEquals(WikipediaResearch.State.NO_DOCUMENTS,research.prepare("Wikipédia : gravité").state());
        assertEquals(2,calls.get());assertEquals(1,network.get());
        var fail=new WikipediaResearch((messages,partial)->new GenerationBackend.Result(
                "{\"terms\":\"INVENTÉ\"}","fixture","STOP",1,1,1,1),
                term->{throw new AssertionError("Web forbidden");},true);
        assertEquals(WikipediaResearch.State.INVALID_PLAN,fail.prepare("Wikipédia : gravité").state());
    }

    @Test void admissionSerializesRequestsAndReleasesExactlyOnce() throws Exception {
        var first=RequestAdmission.acquire("fixture:one",Duration.ofSeconds(1));
        try{
            var second=CompletableFuture.supplyAsync(()->{
                try {var lease=RequestAdmission.acquire("fixture:one",Duration.ofMillis(30));lease.completed();return "ADMITTED";}
                catch(IllegalStateException e){return "BUSY";}
                catch(InterruptedException e){throw new RuntimeException(e);}
            });
            assertEquals("BUSY",second.get(2,TimeUnit.SECONDS));
        }finally{first.completed();}
        var next=RequestAdmission.acquire("fixture:one",Duration.ofSeconds(1));
        assertNotNull(next);next.completed();
    }
}
