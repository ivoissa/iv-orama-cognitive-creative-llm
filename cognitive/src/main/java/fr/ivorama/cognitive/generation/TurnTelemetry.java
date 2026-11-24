package fr.ivorama.cognitive.generation;

import java.util.*;
import java.util.function.Supplier;

/** Thread-confined turn accounting, including discarded generations and failures. Never stores prompts. */
public final class TurnTelemetry implements AutoCloseable {
    public record Call(String stage,long wallNanos,Integer inputTokens,Integer outputTokens,String status) { }
    public record Phase(String name,long wallNanos) { }
    public record Snapshot(long totalNanos,List<Call> calls,List<Phase> phases) {
        public Snapshot { calls=List.copyOf(calls); phases=List.copyOf(phases); }
        public long knownInputTokens(){return calls.stream().filter(c->c.inputTokens()!=null).mapToLong(Call::inputTokens).sum();}
        public long knownOutputTokens(){return calls.stream().filter(c->c.outputTokens()!=null).mapToLong(Call::outputTokens).sum();}
        public boolean completeTokenAccounting(){return calls.stream().allMatch(c->c.inputTokens()!=null&&c.outputTokens()!=null);}
    }
    private static final ThreadLocal<TurnTelemetry> CURRENT=new ThreadLocal<>();
    private final long started=System.nanoTime();
    private final List<Call> calls=new ArrayList<>();
    private final List<Phase> phases=new ArrayList<>();
    private final TurnTelemetry parent;
    private boolean closed;
    private TurnTelemetry(){parent=CURRENT.get();CURRENT.set(this);}
    public static TurnTelemetry start(){return new TurnTelemetry();}
    public static void call(String stage,long wallNanos,Integer inputTokens,Integer outputTokens,String status){
        var current=CURRENT.get(); if(current!=null)current.calls.add(new Call(stage,wallNanos,inputTokens,outputTokens,status));
    }
    public static <T> T phase(String name,Supplier<T> action){
        long started=System.nanoTime();
        try{return action.get();}finally{var current=CURRENT.get();if(current!=null)current.phases.add(new Phase(name,System.nanoTime()-started));}
    }
    public Snapshot snapshot(){return new Snapshot(System.nanoTime()-started,calls,phases);}
    @Override public void close(){if(!closed){CURRENT.set(parent);closed=true;}}
}
