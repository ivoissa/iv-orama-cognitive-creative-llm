package fr.ivorama.cognitive.generation;

import java.util.*;
import java.util.concurrent.*;

/** Deterministic component tests. No model-quality or model-latency claim is made. */
public final class Generation072Cases {
    private Generation072Cases() { }
    public static void main(String[] args) throws Exception {
        sourceGrounding(); uniqueRepairs(); maliciousRepairs(); repairEligibility(); telemetry(); threadIsolation();
        System.out.println("RESULT: 6/6 deterministic component scenarios PASS; NOT a Qwen A/B");
    }
    public static void sourceGrounding() {
        String input="Écris un récit sans le mot « dragon » et utilise le mot « lune ».";
        var c=RequestContract.capture(input,OptionalInt.empty(),List.of("dragon"),List.of("lune"),Optional.empty());
        check(c.task().equals("CREATION") && c.simpleRecognizedTask(),"Simple creation recognized");
        for(var span:c.evidence())check(input.substring(span.start(),span.end()).equals(span.text()),"Exact source offsets");
        expect(()->RequestContract.capture(input,OptionalInt.empty(),List.of("INVENTED"),List.of(),Optional.empty()));
        var unknown=RequestContract.capture("Pourquoi cela arrive-t-il ?",OptionalInt.empty(),List.of(),List.of(),Optional.empty());
        check(!unknown.simpleRecognizedTask(),"Unknown tasks cannot skip interpretation");
        expect(()->GenerationPolicy.instructions(unknown,false));
        System.out.println("C1 source-backed partial contract PASS");
    }
    public static void uniqueRepairs() {
        String draft="Le dragon attend près du quai. La lune éclaire son billet. Il monte dans le train.";
        String patch="{\"edits\":[{\"oldText\":\"dragon\",\"newText\":\"voyageur\"}]}";
        String fixed=TextRepair.apply(draft,patch);
        check(fixed.equals("Le voyageur attend près du quai. La lune éclaire son billet. Il monte dans le train."),"Only unique fragment replaced");
        String swapped=TextRepair.apply("alpha puis beta","{\"edits\":[{\"oldText\":\"alpha\",\"newText\":\"beta\"},{\"oldText\":\"beta\",\"newText\":\"alpha\"}]}");
        check(swapped.equals("beta puis alpha"),"All anchors resolve against the original, not successively mutated text");
        System.out.println("C2 targeted replacements preserve unaffected text PASS");
    }
    public static void maliciousRepairs() {
        expect(()->TextRepair.apply("x x","{\"edits\":[{\"oldText\":\"x\",\"newText\":\"y\"}]}"));
        expect(()->TextRepair.apply("abc","{\"edits\":[{\"oldText\":\"ab\",\"newText\":\"x\"},{\"oldText\":\"bc\",\"newText\":\"y\"}]}"));
        expect(()->TextRepair.apply("abc","{\"edits\":[{\"oldText\":\"missing\",\"newText\":\"x\"}]}"));
        expect(()->TextRepair.apply("abc","{\"edits\":[],\"execute\":\"anything\"}"));
        expect(()->TextRepair.apply("abc","{\"edits\":[],\"edits\":[]}"));
        expect(()->TextRepair.apply("abc","{\"edits\":[]} {}"));
        System.out.println("C3 absent/ambiguous/overlapping anchors and injected schemas refused PASS");
    }
    public static void repairEligibility() {
        check(TextRepair.eligible("x".repeat(256),List.of("FORBIDDEN_TERM: x"),false),"Literal repair eligible");
        check(!TextRepair.eligible("draft",List.of("FORBIDDEN_TERM: x"),false),"Tiny drafts avoid JSON repair overhead");
        check(!TextRepair.eligible("draft",List.of("TRUNCATED: x"),false),"Truncation requires full regeneration");
        check(!TextRepair.eligible("draft",List.of("FORBIDDEN_TERM: x"),true),"Structured analyses never receive fragment repair");
        check(!TextRepair.eligible("x".repeat(8001),List.of("REQUIRED_TERM: x"),false),"Oversized draft refused");
        System.out.println("C4 repair selection remains bounded PASS");
    }
    public static void telemetry() {
        try(var scope=TurnTelemetry.start()) {
            TurnTelemetry.call("generation",7,120,40,"STOP");
            TurnTelemetry.call("repair",3,150,10,"STOP");
            TurnTelemetry.phase("validation",()->true);
            var s=scope.snapshot();
            check(s.calls().size()==2 && s.knownInputTokens()==270 && s.knownOutputTokens()==50,"Discarded draft tokens included");
            check(s.completeTokenAccounting() && s.phases().size()==1,"Complete status and phase");
            try(var nested=TurnTelemetry.start()){
                TurnTelemetry.call("nested",1,null,null,"ERROR");
                check(!nested.snapshot().completeTokenAccounting(),"Unknown tokens remain unknown");
            }
            check(scope.snapshot().calls().size()==2,"Nested scope isolated");
        }
        System.out.println("C5 telemetry accounts for all attempts and unknown tokens PASS");
    }
    public static void threadIsolation() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var barrier=new CyclicBarrier(2);
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++){final int token=i+1;futures.add(pool.submit(()->{
                try(var scope=TurnTelemetry.start()){
                    barrier.await();TurnTelemetry.call("parallel",1,token,token,"STOP");
                    return scope.snapshot().knownOutputTokens()==token;
                }
            }));}
            for(var future:futures)check(future.get(),"Thread-local isolation");
        }finally{pool.shutdownNow();}
        System.out.println("C6 concurrent telemetry isolation PASS");
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static void expect(Runnable action){try{action.run();throw new AssertionError("Expected refusal");}catch(IllegalArgumentException expected){}}
}
