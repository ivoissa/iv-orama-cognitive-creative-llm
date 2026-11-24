package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ivorama.cognitive.agent.*;
import fr.ivorama.cognitive.generation.TurnTelemetry;
import java.nio.file.*;
import java.util.*;

/** Three fixed development cases; real Ollama only, no fixture backend or implicit substitution. */
public final class Reasoned076Comparison {
    public static void main(String[] args) throws Exception {
        Path fixtures=Path.of(args[0]); Path output=Path.of(args[1]); Files.createDirectories(output);
        var json = new ObjectMapper().writerWithDefaultPrettyPrinter();
        var config = LocalLlmConfig.fromSystemProperties().withLimits(256, java.time.Duration.ofSeconds(90));
        String question="Quelle hypothèse reste justifiée sous le modèle fourni ? Signale toute insuffisance ou contradiction.";
        for(String id:List.of("R1","R2","R3")) {
            var plan=ReasonedObserverSession.Plan.load(fixtures.resolve(id+"-plan.json"));
            long start=System.nanoTime();
            var offline=new ReasonedObserverSession(new ReadOnlyWorkspace(fixtures.resolve(id)),plan,null).investigate(question);
            long nanos=System.nanoTime()-start;
            Files.writeString(output.resolve(id+"-without-qwen.json"),json.writeValueAsString(Map.of("result",snapshot(offline),"wallNanos",nanos,"modelCalls",0)));
            try(var telemetry=TurnTelemetry.start()) {
                start=System.nanoTime();
                var delegate=OllamaGenerationBackend.reasonedComment(config);
                GenerationBackend capture=(messages,partial)-> {
                    var response=delegate.generate(messages,partial);
                    try { Files.writeString(output.resolve(id+"-qwen-raw.json"),json.writeValueAsString(Map.of("messages",messages.stream().map(Object::toString).toList(),"response",response))); }
                    catch(java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                    return response;
                };
                var online=QwenObserverAgent.investigateReasoned(fixtures.resolve(id),plan,question,capture);
                long wall=System.nanoTime()-start;
                boolean same=offline.finding().equals(online.finding()) && offline.steps().equals(online.steps());
                Files.writeString(output.resolve(id+"-with-qwen.json"),json.writeValueAsString(Map.of(
                        "result",snapshot(online),"wallNanos",wall,"telemetry",telemetry.snapshot(),"sameCore",same)));
                System.out.println(id+" : "+online.finding().state()+" ; sameCore="+same+" ; commentaryError="+online.commentaryError());
                if(!same) throw new AssertionError("Core diverged");
            }
        }
    }
    private static Map<String,Object> snapshot(ReasonedObserverSession.Result result) {
        var f=result.finding();
        Map<String,Object> finding=new LinkedHashMap<>();
        finding.put("state",f.state()); finding.put("answer",f.answer()); finding.put("cause",f.cause());
        finding.put("possible",f.possible()); finding.put("refuted",f.refuted()); finding.put("reason",f.reason());
        finding.put("nextObservation",f.nextObservation().orElse("")); finding.put("version",f.version());
        finding.put("questionId",f.questionId()); finding.put("reopened",f.reopened());
        return Map.of("report",result.report(),"finding",finding,"steps",result.steps(),"facts",result.facts(),"commentaryError",result.commentaryError());
    }
}
