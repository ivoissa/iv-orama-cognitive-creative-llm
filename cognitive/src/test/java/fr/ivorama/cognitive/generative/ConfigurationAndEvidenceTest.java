package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.agent.ObserverSession;
import fr.ivorama.cognitive.generation.TurnBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationAndEvidenceTest {
    @TempDir Path root;
    @Test void explicitBadBudgetsIdentifyTheKeyWithoutFallingBack() {
        for(String key:List.of("ivorama.agent.timeout","ivorama.agent.outputBudget"))
            for(String value:List.of("", "abc", "-1", "9223372036854775808")) {
                Properties p=new Properties();p.setProperty(key,value);
                assertTrue(assertThrows(IllegalArgumentException.class,()->AgentBudget.Settings.from(p)).getMessage().contains(key));
            }
        var defaults=AgentBudget.Settings.from(new Properties());assertEquals(3072,defaults.outputTokens());assertEquals(Duration.ofSeconds(240),defaults.timeout());
    }
    @Test void injectedBudgetRemainsBoundedAndNestedScopesReuseTheOuterBudget() {
        var settings=new AgentBudget.Settings(32,Duration.ofSeconds(2));
        try(var scope=AgentBudget.open(settings)) {
            var outer=TurnBudget.current();assertEquals(32,outer.remainingOutputTokens());
            try(var nested=AgentBudget.open(new AgentBudget.Settings(64,Duration.ofSeconds(5)))) {assertSame(outer,TurnBudget.current());}
            assertSame(outer,TurnBudget.current());
        }
        assertNull(TurnBudget.current());
    }
    @Test void generationDefaultsAndSnapshotAreExplicit() {
        var p=new Properties();assertEquals(new GenerationOptions(null,null),GenerationOptions.from(p,true));assertEquals(new GenerationOptions(42,false),GenerationOptions.from(p,false));
        p.setProperty("ivorama.llm.seed","123");p.setProperty("ivorama.llm.think","true");var options=GenerationOptions.from(p,false);p.setProperty("ivorama.llm.seed","999");assertEquals(new GenerationOptions(123,true),options);
        p.setProperty("ivorama.llm.think","invalid");assertTrue(assertThrows(IllegalArgumentException.class,()->GenerationOptions.from(p,true)).getMessage().contains("ivorama.llm.think"));
    }
    @Test void citationDiagnosticsAreSpecificButNeverContainDocumentarySecrets() {
        var evidence=List.of(new ObserverSession.Evidence("F1","READ","x.java","return 1; SECRET_DOC",1,false));
        String response="{\"claims\":[{\"kind\":\"OBSERVED\",\"text\":\"SECRET_TEXT\",\"sources\":[{\"id\":\"F99\",\"quote\":\"SECRET_QUOTE\"}]}]}";
        var ex=assertThrows(EvidenceSynthesis.Rejected.class,()->EvidenceSynthesis.validateAndRender(response,evidence));
        assertEquals(EvidenceSynthesis.RejectionReason.UNKNOWN_SOURCE_ID,ex.reason());assertEquals(0,ex.claimIndex());assertEquals(0,ex.sourceIndex());assertFalse(ex.toString().contains("SECRET"));assertNull(ex.getCause());
        ex=assertThrows(EvidenceSynthesis.Rejected.class,()->EvidenceSynthesis.validateAndRender(response.replace("F99","F1"),evidence));assertEquals(EvidenceSynthesis.RejectionReason.QUOTE_NOT_FOUND,ex.reason());
    }
    @Test void malformedJsonDoesNotExposeItsParserCauseAndUnknownMayStillAbstain() {
        var ex=assertThrows(EvidenceSynthesis.Rejected.class,()->EvidenceSynthesis.validateAndRender("{\"SECRET\":",List.of()));assertEquals(EvidenceSynthesis.RejectionReason.MALFORMED_JSON,ex.reason());assertNull(ex.getCause());assertFalse(ex.toString().contains("SECRET"));
        assertTrue(EvidenceSynthesis.validateAndRender("{\"claims\":[{\"kind\":\"UNKNOWN\",\"text\":\"Je ne sais pas\",\"sources\":[]}]}",List.of()).contains("Information manquante : Je ne sais pas"));
    }
    @Test void commandGuardRejectsInvalidInputsBeforeSubstring() {
        assertFalse(FormalReasoningBridge.requested(null));assertFalse(FormalReasoningBridge.requested(":raisonner"));assertTrue(FormalReasoningBridge.requested(":raisonner test"));
        for(String input:List.of("", "abc", ":autre test"))assertThrows(IllegalArgumentException.class,()->new FormalReasoningBridge().evaluate(input));
        assertThrows(IllegalArgumentException.class,()->new FormalReasoningBridge().evaluate(":raisonner "));
    }
}
