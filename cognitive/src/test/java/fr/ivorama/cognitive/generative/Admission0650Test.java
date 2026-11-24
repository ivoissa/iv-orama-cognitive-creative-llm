package fr.ivorama.cognitive.generative;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic admission invariants, without Ollama and without network. */
class Admission0650Test {
    @Test void aClientTimeoutDoesNotReleaseTheNativeGate() throws Exception {
        var first=RequestAdmission.acquire("0650/timeout",Duration.ofSeconds(1));
        first.callerTimedOut();
        var error=assertThrows(IllegalStateException.class,()->RequestAdmission.acquire("0650/timeout",Duration.ofMillis(100)));
        assertTrue(error.getMessage().contains("OLLAMA_APRES_TIMEOUT"));
        first.completed(); // simulated native completion signal arrives later
        var next=RequestAdmission.acquire("0650/timeout",Duration.ofSeconds(1));
        next.completed();
    }
    @Test void duplicateCompletionNeverCreatesASecondPermit() throws Exception {
        var first=RequestAdmission.acquire("0650/duplicate",Duration.ofSeconds(1));
        first.completed();first.completed();
        var second=RequestAdmission.acquire("0650/duplicate",Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class,()->RequestAdmission.acquire("0650/duplicate",Duration.ofMillis(20)));
        second.completed();
    }
    @Test void anUnknownTransportFailureQuarantinesTheKey() throws Exception {
        var first=RequestAdmission.acquire("0650/unknown",Duration.ofSeconds(1));
        first.indeterminate("perte du flux");first.completed();
        var error=assertThrows(IllegalStateException.class,()->RequestAdmission.acquire("0650/unknown",Duration.ofSeconds(1)));
        assertTrue(error.getMessage().contains("OLLAMA_ETAT_INDETERMINE"));
    }
    @Test void currentInFlightWithoutTimeoutStillQueues() throws Exception {
        var first=RequestAdmission.acquire("0650/queue",Duration.ofSeconds(1));
        var executor=Executors.newSingleThreadExecutor();
        try {
            var later=executor.submit(()->{
                try {
                    var lease=RequestAdmission.acquire("0650/queue",Duration.ofSeconds(2));
                    lease.completed();return true;
                }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
            });
            assertThrows(TimeoutException.class,()->later.get(30,TimeUnit.MILLISECONDS));
            first.completed();
            assertTrue(later.get(2,TimeUnit.SECONDS));
        }finally{first.completed();executor.shutdownNow();}
    }
}
