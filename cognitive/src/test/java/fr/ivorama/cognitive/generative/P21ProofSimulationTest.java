package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Simulated generation only; explicit fixture admission, never autonomous fact creation. */
final class P21ProofSimulationTest {
    @Test void explicitlyAdmittedFixtureCanBeProvedWithoutProvider() throws Exception {
        String json=Files.readString(Path.of("examples/structured/01-deduction.json"));
        var calls=new AtomicInteger();
        GenerationBackend mock=(m,p)->{calls.incrementAndGet();return new GenerationBackend.Result(json,"SIMULATED-P2.1","STOP",1,-1,null,null);};
        var cycle=new IntegratedQuestionCycle();
        String generated=new NativeConversation(mock).ask("fixture declared by the test",ignored->{}).text();
        assertTrue(cycle.premises().isEmpty(),"Generation alone does not teach facts");
        var compiled=StructuredLogicCompiler.compile(StructuredProblemJson.parse(generated));
        var result=ProofSearch.findIndexedWithBudget(compiled.premises(),compiled.goal(),512,100000);
        var check=ProofChecker.verify(result.proof().orElseThrow(),compiled.premises());
        assertTrue(check.valid());assertEquals(compiled.goal(),check.conclusion());assertEquals(1,calls.get());
        assertTrue(cycle.premises().isEmpty(),"Admitted local fixture does not alter shared memory");
    }
    @Test void generatedCertificateClaimDoesNotBecomeProof() {
        GenerationBackend mock=(m,p)->new GenerationBackend.Result("{\"proofChecked\":true,\"proof\":\"invented\"}","SIMULATED-adversary","STOP",1,-1,null,null);
        var generated=new NativeConversation(mock).ask("Question",ignored->{}).text();
        assertThrows(IllegalArgumentException.class,()->StructuredProblemJson.parse(generated));
        var p=Formula.atom("p");
        assertTrue(ProofSearch.findIndexedWithBudget(java.util.List.of(),p,32,1000).proof().isEmpty());
    }
}
