package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** SIMULATED deterministic GenerationBackend; no Ollama and no real translation-quality claim. */
class LogicNgSimulatedIntegrationTest {
    @Test void fixtureGenerationThroughExplicitStructuredRoutePreservesCheckedProof() throws Exception {
        String json=Files.readString(Path.of("examples/structured/01-deduction.json"));
        AtomicInteger calls=new AtomicInteger();
        GenerationBackend fixture=(messages,partial)->{
            calls.incrementAndGet();partial.accept(json);
            return new GenerationBackend.Result(json,"SIMULATED-fixture","STOP",1,-1,null,null);
        };
        var conversation=new NativeConversation(fixture);
        String generated=conversation.ask("fixture: declared finite symbolic problem",ignored->{}).text();
        // Explicit caller admission for this fixture only, not silent trust in generated premises.
        var before=new FormalReasoningBridge().evaluate(":raisonner "+generated);
        var after=new FormalReasoningBridge(new LogicNgSatBackend()).evaluate(":raisonner "+generated);
        assertEquals(before.receipt().status(),after.receipt().status());
        assertEquals("TRUE_CONDITIONALLY",after.receipt().status());
        assertTrue(before.receipt().proofChecked());assertTrue(after.receipt().proofChecked());
        assertEquals(before.text(),after.text());assertEquals(1,calls.get());
    }
    @Test void allStructuredFixturesAndAutomaticFrenchHaveSameResultsWithSatAudit() throws Exception {
        for(String name:java.util.List.of("01-deduction","02-indetermination","03-contradiction","04-negation","05-incomplete")) {
            String command=":raisonner "+Files.readString(Path.of("examples/structured/"+name+".json"));
            var a=new FormalReasoningBridge().evaluate(command);
            var b=new FormalReasoningBridge(new LogicNgSatBackend()).evaluate(command);
            assertEquals(a.text(),b.text(),name);assertEquals(a.receipt(),b.receipt(),name);
        }
        String french="Le module z3 est actif. Le module z3 est-il actif ?";
        var a=new FormalReasoningBridge().tryAutomatic(french).orElseThrow();
        var b=new FormalReasoningBridge(new LogicNgSatBackend()).tryAutomatic(french).orElseThrow();
        assertEquals(a,b);
    }
    @Test void fabricatedCertificateTextIsRejectedAsStructuredInput() {
        GenerationBackend fixture=(m,p)->new GenerationBackend.Result("Oui, preuve formelle certifiée sans prémisses.","SIMULATED-adversary","STOP",1,-1,null,null);
        String text=new NativeConversation(fixture).ask("Question",ignored->{}).text();
        var result=new FormalReasoningBridge(new LogicNgSatBackend()).evaluate(":raisonner {\"proof\":\""+text+"\"}");
        assertFalse(result.receipt().proofChecked());assertEquals("INVALID_INPUT",result.receipt().status());
    }
}
