package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import fr.ivorama.cognitive.ModelExplorer;
import fr.ivorama.cognitive.P31CandidateAnswerAudit;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Offline probe: real production core + real P3.2 adapter; only SDK message types are stubbed. */
public final class P32ObserveProbe {
    private static int checks;
    private static final String FACTS = "La porte A est ouverte. Le document B est valide.";
    private static void ok(boolean valid, String detail) {
        checks++;
        if (!valid) throw new AssertionError("P3.2: " + detail);
    }
    private static GenerationBackend fake(String model, String response, String finish, Consumer<String> onStream) {
        return (messages, partial) -> {
            onStream.accept("called");
            partial.accept(response.substring(0, Math.min(3,response.length())));
            return new GenerationBackend.Result(response,model,finish,123456,789,11,22);
        };
    }
    private static ObservedGenerationBackend.Observation once(String label,String draft,String finish) {
        List<ObservedGenerationBackend.Observation> found = new ArrayList<>();
        var decorated = new ObservedGenerationBackend(fake(label,draft,finish, ignored -> {}), FACTS,
                ModelExplorer.Budget.small(),found::add);
        var before = new GenerationBackend.Result(draft,label,finish,123456,789,11,22);
        var got=decorated.generate(List.of(UserMessage.from("question")), ignored->{});
        ok(got.equals(before),"full original generation result preserved");
        ok(found.size()==1,"one observation for one successful call");
        ok(found.get(0).generation()==got,"observation references the exact returned result");
        return found.get(0);
    }
    public static void main(String[] unused) {
        checks=0;
        var yes=once("model-a","La porte A est ouverte.","STOP");
        ok(yes.state()==ObservedGenerationBackend.AuditState.ASSESSED,"normal completion");
        ok(yes.fullySupported(),"entailed draft");
        ok(yes.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.ENTAILED,"entailed classification");
        ok(yes.auditNanos()>=0,"auditing time nonnegative");
        var no=once("model-b","La porte A n'est pas ouverte.","STOP");
        ok(!no.fullySupported(),"contradicted draft not certified");
        ok(no.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.CONTRADICTED,"contradiction detected");
        var unsupported=once("model-c","La porte A est fermée.","STOP");
        ok(unsupported.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.CONSISTENT_UNSUPPORTED,
                "no invented antonym entailment");
        ok(!unsupported.fullySupported(),"unsupported not certified");
        var unknown=once("model-d","Je ne peux pas conclure.","STOP");
        ok(unknown.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,
                "natural language outside fragment is unresolved");
        ok(!unknown.fullySupported(),"untranslated is not certified");
        var truncated=once("model-e","La porte A est ouverte.","LENGTH");
        ok(truncated.state()==ObservedGenerationBackend.AuditState.TRUNCATED,"truncation state");
        ok(!truncated.fullySupported(),"truncation not fully supported");
        var incomplete=once("model-e","La porte A est ouverte.",null);
        ok(incomplete.state()==ObservedGenerationBackend.AuditState.UNCONFIRMED_FINISH,"unknown finish");
        ok(!incomplete.fullySupported(),"unknown finish not certified");
        for(String label: List.of("fixture-qwen","fixture-gpt","fixture-anthropic","fixture-other")) {
            var sample=once(label,"La porte A est ouverte.","STOP");
            ok(sample.fullySupported(),"model agnostic " + label);
            ok(sample.review().orElseThrow().providerLabel().equals(label),"model labeling " + label);
        }
        var unknownProvider=once(null,"La porte A est ouverte.","STOP");
        ok(unknownProvider.review().orElseThrow().providerLabel().equals("unknown-provider"),"null provider label");
        ok(unknownProvider.fullySupported(),"null label does not change reasoning");
        var tooLongLabel=once("z".repeat(200),"La porte A est ouverte.","STOP");
        ok(tooLongLabel.review().orElseThrow().providerLabel().length()==128,"bounded provider label");
        var tooLongDraft=once("model", "A".repeat(6010),"STOP");
        ok(tooLongDraft.review().orElseThrow().status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,
                "oversized draft not interpreted");
        var lowBudget = new ArrayList<ObservedGenerationBackend.Observation>();
        var restricted=new ObservedGenerationBackend(fake("model","La porte A est ouverte.","STOP",s->{}),FACTS,
                new ModelExplorer.Budget(0,1),lowBudget::add);
        restricted.generate(List.of(),s->{});
        ok(lowBudget.get(0).review().orElseThrow().status()==P31CandidateAnswerAudit.Status.RESOURCE_LIMIT,
                "resource limit is explicit");
        ok(!lowBudget.get(0).fullySupported(),"no certification on resource limit");
        AtomicInteger calls=new AtomicInteger();
        GenerationBackend brokenProvider=(messages,partial)->{calls.incrementAndGet();throw new IllegalStateException("offline");};
        var broken=new ObservedGenerationBackend(brokenProvider,FACTS,ModelExplorer.Budget.small(),item->calls.addAndGet(100));
        try {broken.generate(List.of(),s->{});throw new AssertionError("failure must propagate");}
        catch(IllegalStateException expected) {ok("offline".equals(expected.getMessage()),"no simulated fallback on provider failure");}
        ok(calls.get()==1,"no audit after provider failure");
        AtomicInteger ignored=new AtomicInteger();
        var brokenSink=new ObservedGenerationBackend(fake("model","La porte A est ouverte.","STOP",s->{}),FACTS,
                ModelExplorer.Budget.small(), item->{ignored.incrementAndGet();throw new IllegalArgumentException("sink failure");});
        ok(brokenSink.generate(List.of(),s->{}).text().equals("La porte A est ouverte."),
                "observer failure never mutates answer");
        ok(ignored.get()==1,"observer called despite its failure");
        AtomicInteger streaming=new AtomicInteger();
        var obs=new ArrayList<ObservedGenerationBackend.Observation>();
        var wrapped=new ObservedGenerationBackend(fake("provider","La porte A est ouverte.","STOP",s->{}),FACTS,
                ModelExplorer.Budget.small(), obs::add);
        var chat=new NativeConversation(wrapped);
        var response1=chat.ask("première question",chunk->{ok(chunk.equals("La "),"stream chunk unchanged");streaming.incrementAndGet();});
        var response2=chat.ask("deuxième question",chunk->streaming.incrementAndGet());
        ok(response1.text().equals(response2.text()),"multi-turn untouched");
        ok(streaming.get()==2,"streaming forwarded every call");
        ok(chat.history().size()==4,"native history preserved");
        ok(obs.size()==2 && obs.stream().allMatch(ObservedGenerationBackend.Observation::fullySupported),
                "generation history not added to declared reference");
        try{new ObservedGenerationBackend(wrapped," ",ModelExplorer.Budget.small(),obs::add);
            throw new AssertionError("missing trusted reference must fail");}
        catch(IllegalArgumentException expected) {ok(true,"empty reference refused");}
        try{new ObservedGenerationBackend(wrapped,"A".repeat(6001),ModelExplorer.Budget.small(),obs::add);
            throw new AssertionError("too long reference must fail");}
        catch(IllegalArgumentException expected) {ok(true,"oversized reference refused");}
        System.out.println("P32ObserveProbe PASS: " + checks + " assertions, real core + test-only SDK stubs, no model invocation");
    }
}
