package fr.ivorama.cognitive.generative;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Per-JVM admission for one active request per endpoint/model.
 *
 * <p>In contrast to a lexical try-with-resources lock, the gate belongs to the
 * native request until the SDK confirms its completion. A caller timeout is not
 * proof that Ollama stopped computing. Unknown terminal transport failures are
 * quarantined: restarting this JVM must be preceded by checking/resetting the
 * local Ollama server. Cross-JVM workloads require server-side admission too.
 */
final class RequestAdmission {
    private static final ConcurrentMap<String, Gate> GATES = new ConcurrentHashMap<>();
    private RequestAdmission() { }

    private static final class Gate {
        final Semaphore semaphore = new Semaphore(1, true);
        volatile String quarantineReason;
        volatile boolean timedOutPending;

        void check() {
            if (quarantineReason != null) {
                throw new IllegalStateException("OLLAMA_ETAT_INDETERMINE : " + quarantineReason
                        + " ; vérifier/arrêter la génération Ollama puis redémarrer IV-ORAMA");
            }
            if (timedOutPending) {
                throw new IllegalStateException("OLLAMA_APRES_TIMEOUT : génération précédente potentiellement active ; attente de sa fin confirmée");
            }
        }
    }

    static Lease acquire(String endpointAndModel, Duration budget) throws InterruptedException {
        Objects.requireNonNull(endpointAndModel);
        Objects.requireNonNull(budget);
        if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Budget d'admission invalide");
        Gate gate = GATES.computeIfAbsent(endpointAndModel, ignored -> new Gate());
        gate.check();
        if (!gate.semaphore.tryAcquire(budget.toNanos(), TimeUnit.NANOSECONDS)) {
            gate.check();
            throw new IllegalStateException("OLLAMA_OCCUPE : limite d'attente locale atteinte");
        }
        try {
            gate.check();
            return new Lease(gate);
        } catch (RuntimeException exception) {
            gate.semaphore.release();
            throw exception;
        }
    }

    /** Owned by the SDK callbacks, not by the method waiting for the result. */
    static final class Lease {
        private final Gate gate;
        private boolean terminated;
        private Lease(Gate gate) { this.gate = gate; }

        /** A local timeout does NOT release the gate. Completion may still arrive later. */
        synchronized void callerTimedOut() {
            if (!terminated) gate.timedOutPending = true;
        }

        /** Only a normal, complete server response proves this request is no longer active. */
        synchronized void completed() {
            if (terminated) return;
            terminated = true;
            gate.timedOutPending = false;
            if (gate.quarantineReason == null) gate.semaphore.release();
        }

        /** The server may still compute after a transport error or failed dispatch. Fail closed. */
        synchronized void indeterminate(String reason) {
            if (terminated) return;
            terminated = true;
            gate.quarantineReason = Objects.requireNonNull(reason);
            gate.timedOutPending = false;
            // Intentionally NEVER release the semaphore while the server state is unknown.
        }
    }
}
