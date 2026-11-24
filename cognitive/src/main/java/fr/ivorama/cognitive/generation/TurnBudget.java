package fr.ivorama.cognitive.generation;

import java.time.Duration;

/** One scope covers planning, research planning and all real generations, including rejected drafts.
 * Unknown native usage consumes the entire allocation; elapsed time never cancels/releases a native lease. */
public final class TurnBudget implements AutoCloseable {
    public record Allocation(int outputTokens, Duration timeout) { }
    private static final ThreadLocal<TurnBudget> CURRENT=new ThreadLocal<>();
    private final TurnBudget parent;
    private final long deadline;
    private int remaining;
    private boolean closed;
    public TurnBudget(int outputTokens,Duration timeout){
        if(outputTokens<16||outputTokens>8192||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofMinutes(30))>0)
            throw new IllegalArgumentException("Budget de tour hors limites");
        remaining=outputTokens;deadline=System.nanoTime()+timeout.toNanos();parent=CURRENT.get();CURRENT.set(this);
    }
    public static TurnBudget current(){return CURRENT.get();}
    public Allocation allocate(int maximum,Duration timeout){
        long nanos=deadline-System.nanoTime();
        if(remaining<16||nanos<Duration.ofSeconds(1).toNanos())throw new IllegalStateException("TURN_BUDGET_EXHAUSTED: aucun nouvel appel autorisé");
        return new Allocation(Math.min(maximum,remaining),Duration.ofNanos(Math.min(nanos,timeout.toNanos())));
    }
    public void consumed(Allocation allocation,Integer actual){
        remaining-=actual==null?allocation.outputTokens():Math.max(0,actual);
    }
    public Duration remainingTime(){return Duration.ofNanos(Math.max(0,deadline-System.nanoTime()));}
    public int remainingOutputTokens(){return remaining;}
    @Override public void close(){if(!closed){CURRENT.set(parent);closed=true;}}
}
