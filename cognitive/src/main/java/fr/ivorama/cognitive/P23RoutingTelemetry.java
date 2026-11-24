package fr.ivorama.cognitive;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * P2.3b opt-in measurements of the *whole* read-only routing call and of the
 * semantic provider. No timer here pretends to interrupt a blocking provider;
 * use LogicNgExplanationService.Limits for cooperative solver bounds.
 * Allocation counts are per-thread, optional JDK/VM instrumentation (-1 if
 * unavailable), NOT process-wide heap usage or GC-inclusive allocation.
 */
public final class P23RoutingTelemetry {
    private P23RoutingTelemetry() {}

    public record Measurement(Optional<P23ProofRouting.Receipt> receipt,
                              long totalWallNanos, long providerWallNanos,
                              int explainCalls, int matchesCalls,
                              long threadAllocatedBytes) {
        public Measurement {
            Objects.requireNonNull(receipt);
            if (totalWallNanos < 0 || providerWallNanos < 0 || explainCalls < 0
                    || matchesCalls < 0 || threadAllocatedBytes < -1)
                throw new IllegalArgumentException("Invalid routing measurement");
        }
        public boolean hasCheckedProof() {
            return receipt.isPresent() && receipt.orElseThrow().proofChecked();
        }
    }

    private static long threadAllocation() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean extended
                && extended.isThreadAllocatedMemorySupported()
                && extended.isThreadAllocatedMemoryEnabled()) {
            return extended.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    /** Measure without changing the cycle's routing/certification logic. */
    public static Measurement measure(IntegratedQuestionCycle cycle, String questionId,
                                      SemanticExplanationProvider provider,
                                      P23ProofRouting.Options options) {
        Objects.requireNonNull(cycle); Objects.requireNonNull(provider);
        Objects.requireNonNull(options);
        final class MeteredProvider implements SemanticExplanationProvider {
            long wallNanos;
            int explained, matched;
            @Override public SemanticExplanation explain(
                    Collection<SemanticExplanation.Premise> premises, Formula goal) {
                explained++;
                long start = System.nanoTime();
                try { return provider.explain(premises, goal); }
                finally { wallNanos += System.nanoTime() - start; }
            }
            @Override public boolean matches(SemanticExplanation explanation,
                    Collection<SemanticExplanation.Premise> premises, Formula goal) {
                matched++;
                long start = System.nanoTime();
                try { return provider.matches(explanation, premises, goal); }
                finally { wallNanos += System.nanoTime() - start; }
            }
        }
        var metered = new MeteredProvider();
        long allocationBefore = threadAllocation();
        long start = System.nanoTime();
        var receipt = cycle.proofRoutingReceipt(questionId, metered, options);
        long elapsed = System.nanoTime() - start;
        long allocationAfter = threadAllocation();
        long delta = allocationBefore < 0 || allocationAfter < allocationBefore
                ? -1 : allocationAfter - allocationBefore;
        return new Measurement(receipt, elapsed, metered.wallNanos,
                metered.explained, metered.matched, delta);
    }
}
