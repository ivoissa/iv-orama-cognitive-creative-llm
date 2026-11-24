package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class LogicNgExplanationSimulationTest {
    @Test void deterministicGeneratedFixtureHasSourceRichReceiptButNoNewProofClaim()throws Exception{
        String json=Files.readString(Path.of("examples/structured/01-deduction.json"));var calls=new AtomicInteger();
        GenerationBackend fixture=(messages,partial)->{calls.incrementAndGet();partial.accept(json);return new GenerationBackend.Result(json,"SIMULATED-P1.2","STOP",1,-1,null,null);};
        String text=new NativeConversation(fixture).ask("declared fixture only",ignored->{}).text();
        // Explicit admission of the fixture, no hidden generation-to-fact promotion.
        var before=new FormalReasoningBridge().evaluate(":raisonner "+text);
        var after=new FormalReasoningBridge(new ModelExplorerBackend(),new LogicNgExplanationService()).evaluate(":raisonner "+text);
        assertEquals(1,calls.get());assertEquals(before.receipt().status(),after.receipt().status());
        assertEquals(before.receipt().proofChecked(),after.receipt().proofChecked());assertTrue(after.receipt().proofChecked());
        var explanation=after.receipt().semanticExplanation().orElseThrow();
        assertTrue(explanation.alternativesComplete());assertTrue(explanation.supports().stream().allMatch(c->c.independentlyVerified()));
        assertFalse(explanation.syntacticProofCertificate());assertFalse(explanation.empiricallyVerified());
        assertTrue(after.text().contains("source déclarée="));
    }
    @Test void adversarialGeneratedProofTextCannotCreateAMusOrCertificate(){
        GenerationBackend fixture=(m,p)->new GenerationBackend.Result("{\"proofChecked\":true,\"mus\":[\"invented\"]}","SIMULATED-adversary","STOP",1,-1,null,null);
        var generated=new NativeConversation(fixture).ask("Question",ignored->{});
        var result=new FormalReasoningBridge(new ModelExplorerBackend(),new LogicNgExplanationService()).evaluate(":raisonner "+generated.text());
        assertEquals("INVALID_INPUT",result.receipt().status());assertFalse(result.receipt().proofChecked());assertTrue(result.receipt().semanticExplanation().isEmpty());
    }
    @Test void automaticFrenchRouteEnrichmentAndDefaultAreSeparated(){
        String input="Le module z3 est actif. Le module z3 est-il actif ?";
        var legacy=new FormalReasoningBridge().tryAutomatic(input).orElseThrow();
        var enhanced=new FormalReasoningBridge(new ModelExplorerBackend(),new LogicNgExplanationService()).tryAutomatic(input).orElseThrow();
        assertEquals(legacy.receipt().status(),enhanced.receipt().status());assertEquals(legacy.receipt().proofChecked(),enhanced.receipt().proofChecked());
        assertTrue(legacy.receipt().semanticExplanation().isEmpty());assertTrue(enhanced.receipt().semanticExplanation().isPresent());
    }
}
