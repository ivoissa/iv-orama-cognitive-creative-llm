package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Controlled adapter tests; the responses are fixtures, never a measurement of Qwen quality. */
public final class Optimization072Cases {
    private Optimization072Cases() { }
    private static final String PREFIX="Un voyageur attend sur le quai. ".repeat(30);
    private static final String BAD=PREFIX+"Un dragon arrive.";
    private static final String GOOD=PREFIX+"Un ami arrive.";
    private static final String PATCH="{\"edits\":[{\"oldText\":\"dragon\",\"newText\":\"ami\"}]}";
    public static void main(String[] args) {
        controlledComparison(); rejectedRepair(); adaptivePlanning(); sourceAnalysis(); fullRegeneration(); failedTransport();
        System.out.println("RESULT: 6/6 controlled integration scenarios PASS; REAL_QWEN_AB=NOT_RUN");
    }
    static GenerationBackend.Result result(String text) {
        return new GenerationBackend.Result(text,"SCRIPTED_FIXTURE","STOP",1,-1,null,null);
    }
    static GroundedConversation session(GenerationBackend plain,GenerationBackend structured,
                                        RequestPlanner planner,boolean candidate) {
        return new GroundedConversation(new LocalConversation(plain,structured,5),
                new WikipediaResearch((m,p)->{throw new AssertionError("Unexpected research planning");},
                        q->{throw new AssertionError("Unexpected network query");},false),planner,false,
                candidate?GroundedConversation.Options.candidate072():GroundedConversation.Options.legacy());
    }
    public static void controlledComparison() {
        String question="Écris un récit sans le mot « dragon ».";
        // Same bad first draft in all arms. Fixtures prescribe the later answers deliberately.
        GenerationBackend unwrapped=(m,p)->result(BAD);
        String raw=unwrapped.generate(List.of(UserMessage.from(question)),t->{}).text();
        AtomicInteger oldCalls=new AtomicInteger(),newCalls=new AtomicInteger();
        AtomicInteger oldChars=new AtomicInteger(),newChars=new AtomicInteger();
        var legacy=session((m,p)->{String s=oldCalls.incrementAndGet()==1?BAD:GOOD;oldChars.addAndGet(s.length());return result(s);},
                (m,p)->{throw new AssertionError("Unexpected structured old call");},null,false);
        var candidate=session((m,p)->{newCalls.incrementAndGet();newChars.addAndGet(BAD.length());return result(BAD);},
                (m,p)->{newCalls.incrementAndGet();newChars.addAndGet(PATCH.length());return result(PATCH);},null,true);
        var oldTurn=legacy.ask(question,t->{});var newTurn=candidate.ask(question,t->{});
        var rules=OutputVerifier.extract(question);
        check(!OutputVerifier.check(raw,rules,newTurn.research(),false).passed(),"Raw fixture violates its constraint");
        check(oldTurn.generation().text().equals(GOOD)&&newTurn.generation().text().equals(GOOD),"Both wrappers return the same corrected fixture");
        check(newChars.get()<oldChars.get(),"Fragment JSON is shorter than full regeneration for this long fixture");
        check(newTurn.telemetry().calls().size()==2&&!newTurn.telemetry().completeTokenAccounting(),"All calls tracked; fixture token counts unknown");
        System.out.println("AB_FIXTURE literal: RAW=FAIL; IV071=PASS; IV072=PASS; calls="+oldCalls+"/"+newCalls+
                "; emittedCharacters071="+oldChars+"; emittedCharacters072="+newChars+"; qualityGain072=NOT_MEASURED");
    }
    public static void rejectedRepair() {
        var memory=new LocalConversation((m,p)->result(BAD),
                (m,p)->result("{\"edits\":[{\"oldText\":\"arrive\",\"newText\":\"repart\"}]}"),5);
        var s=new GroundedConversation(memory,new WikipediaResearch((m,p)->result("{}"),q->List.of(),false),null,false,
                GroundedConversation.Options.candidate072());
        var visible=new StringBuilder();
        try{s.ask("Écris un récit sans le mot « dragon ».",visible::append);throw new AssertionError("Invalid repair accepted");}
        catch(GroundedConversation.RejectedOutput expected){}
        check(visible.isEmpty()&&memory.history().size()==1,"Rejected drafts neither published nor memorized");
        check(s.lastTelemetry().calls().size()==2,"Failure telemetry retained");
        System.out.println("AB_FIXTURE repair: whole answer revalidated; rejected repair unpublished PASS");
    }
    public static void adaptivePlanning() {
        String plan="{\"tasks\":[\"CREATION\"],\"excerpts\":[],\"missingInformation\":[],\"researchNeed\":\"NONE\",\"terms\":\"\"}";
        var counts=new int[2];var promptChars=new int[2];
        var old=session((m,p)->{counts[0]++;promptChars[0]=chars(m);return result("Un train arrive.");},(m,p)->result("{}"),
                new RequestPlanner((m,p)->{counts[0]++;return result(plan);}),false);
        var next=session((m,p)->{counts[1]++;promptChars[1]=chars(m);return result("Un train arrive.");},(m,p)->result("{}"),
                new RequestPlanner((m,p)->{counts[1]++;return result(plan);}),true);
        var a=old.ask("Écris un récit.",t->{});var b=next.ask("Écris un récit.",t->{});
        check(a.generation().text().equals(b.generation().text()),"Same fixture response");
        check(counts[0]==2&&counts[1]==1,"One planning call avoided on a recognized creation");
        check(promptChars[1]<promptChars[0],"Selected policy plus contract smaller than legacy prompt plus plan");
        System.out.println("AB_FIXTURE routing: calls071="+counts[0]+"; calls072="+counts[1]+"; promptCharacters071="+promptChars[0]+"; promptCharacters072="+promptChars[1]);
    }
    public static void sourceAnalysis() {
        var s=session((m,p)->{throw new AssertionError("Structured analysis expected");},
                (m,p)->result("{\"quotes\":[\"Le train est parti.\"],\"interpretations\":[\"Un départ est rapporté ; sa cause n'est pas donnée.\"]}"),null,true);
        var result=s.ask("Analyse « Le train est parti. ».",t->{});
        check(result.verification().structuredAnalysis()&&result.verification().report().passed(),"Exact quotes verified");
        System.out.println("AB_FIXTURE analysis: exact source quotes verified; interpretation truth NOT_CERTIFIED");
    }
    public static void fullRegeneration() {
        int[] calls={0};
        var s=session((m,p)->result(++calls[0]==1?"Le train arrive. Il repart.":"Le train arrive."),
                (m,p)->{throw new AssertionError("Sentence count must use full regeneration");},null,true);
        var answer=s.ask("Écris un récit en une phrase.",t->{});
        check(calls[0]==2&&answer.generation().text().equals("Le train arrive."),"Unsupported fragment repair uses bounded full regeneration");
        System.out.println("AB_FIXTURE sentence count: full-regeneration fallback PASS");
    }
    public static void failedTransport() {
        var memory=new LocalConversation((m,p)->{throw new IllegalStateException("Controlled transport failure");},5);
        var s=new GroundedConversation(memory,new WikipediaResearch((m,p)->result("{}"),q->List.of(),false),null,false,
                GroundedConversation.Options.candidate072());
        try{s.ask("Bonjour",t->{});throw new AssertionError("Failure hidden");}catch(IllegalStateException expected){}
        check(memory.history().size()==1&&s.lastTelemetry().calls().size()==1&&!s.lastTelemetry().completeTokenAccounting(),"Failed call tracked without invented tokens or memory update");
        System.out.println("AB_FIXTURE transport failure: no fabricated result or token count PASS");
    }
    private static int chars(List<ChatMessage> messages) {
        int count=0;for(var m:messages)count+=m instanceof SystemMessage s?s.text().length():m instanceof UserMessage u?u.singleText().length():((AiMessage)m).text().length();return count;
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
}
