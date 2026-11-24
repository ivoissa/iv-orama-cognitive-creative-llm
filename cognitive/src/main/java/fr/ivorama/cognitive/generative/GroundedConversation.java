package fr.ivorama.cognitive.generative;

import java.util.*;
import fr.ivorama.cognitive.generation.*;
import java.util.function.Consumer;

/** Orchestrates one bounded research then resumes the original task with an auditable receipt. */
public final class GroundedConversation {
    public record Verification(int attempts,OutputVerifier.Report report,boolean structuredAnalysis) { }
    public static final class RejectedOutput extends IllegalStateException {
        private final Verification verification;
        RejectedOutput(Verification verification){super("Aucune réponse validée après "+verification.attempts()+" essais : "+verification.report().violations());this.verification=verification;}
        public Verification verification(){return verification;}
    }
    public record CitationAudit(List<String> unsupportedIds,boolean documentsWithoutCitation) {
        public CitationAudit { unsupportedIds=List.copyOf(unsupportedIds); }
    }
    public record Turn(GenerationBackend.Result generation,WikipediaResearch.Evidence research,CitationAudit citationAudit,long totalNanos,
                       RequestPlanner.Interpretation interpretation,Verification verification,Optional<FormalReasoningBridge.Receipt> formal,TurnTelemetry.Snapshot telemetry) {
        public Turn(GenerationBackend.Result generation,WikipediaResearch.Evidence research,CitationAudit citationAudit,long totalNanos,
                    RequestPlanner.Interpretation interpretation,Verification verification,Optional<FormalReasoningBridge.Receipt> formal){
            this(generation,research,citationAudit,totalNanos,interpretation,verification,formal,new TurnTelemetry.Snapshot(0,List.of(),List.of()));
        }
        public Turn(GenerationBackend.Result generation,WikipediaResearch.Evidence research,CitationAudit citationAudit,long totalNanos){
            this(generation,research,citationAudit,totalNanos,RequestPlanner.legacy());
        }
        public Turn(GenerationBackend.Result generation,WikipediaResearch.Evidence research,CitationAudit citationAudit,long totalNanos,RequestPlanner.Interpretation interpretation){
            this(generation,research,citationAudit,totalNanos,interpretation,new Verification(0,new OutputVerifier.Report(List.of(),List.of()),false),Optional.empty());
        }
    }
    private static final java.util.regex.Pattern CITATION_ID=java.util.regex.Pattern.compile("\\[(W[0-9]+)\\]");
    /** Identifier consistency only: not a semantic verification of statements against documents. */
    static CitationAudit audit(String text,WikipediaResearch.Evidence evidence){
        var allowed=new HashSet<String>();evidence.sources().forEach(s->allowed.add(s.id()));
        var unsupported=new TreeSet<String>();boolean cited=false;
        var matcher=CITATION_ID.matcher(text);
        while(matcher.find()){if(!allowed.contains(matcher.group(1)))unsupported.add(matcher.group(1));else cited=true;}
        return new CitationAudit(List.copyOf(unsupported),!allowed.isEmpty()&&!cited);
    }
    private final LocalConversation conversation;
    private final WikipediaResearch research;
    private final RequestPlanner planner;
    private final boolean automaticReasoning;
    private final TaskState taskState=new TaskState();
    public record Options(boolean requestContract,boolean compactPrompt,boolean adaptivePlanner,boolean targetedRepair){
        public static Options legacy(){return new Options(false,false,false,false);}
        public static Options candidate072(){return candidate073();}
        public static Options candidate073(){return new Options(true,true,true,true);}
    }
    private final Options options;
    private TurnTelemetry.Snapshot lastTelemetry=new TurnTelemetry.Snapshot(0,List.of(),List.of());
    public synchronized TurnTelemetry.Snapshot lastTelemetry(){return lastTelemetry;}
    public GroundedConversation(LocalConversation conversation,WikipediaResearch research){
        this(conversation,research,null);
    }
    public GroundedConversation(LocalConversation conversation,WikipediaResearch research,RequestPlanner planner){
        this(conversation,research,planner,true);
    }
    public GroundedConversation(LocalConversation conversation,WikipediaResearch research,RequestPlanner planner,boolean automaticReasoning){
        this(conversation,research,planner,automaticReasoning,Options.legacy());
    }
    public GroundedConversation(LocalConversation conversation,WikipediaResearch research,RequestPlanner planner,
                                boolean automaticReasoning,Options options){
        this.options=Objects.requireNonNull(options);
        this.conversation=Objects.requireNonNull(conversation);this.research=Objects.requireNonNull(research);
        this.planner=planner;
        this.automaticReasoning=automaticReasoning;
    }
    public synchronized Turn ask(String input,Consumer<String> partial){return measuredAsk(input,partial,null);}
    /** Benchmark/document adapter: same already-retrieved evidence can be supplied to both versions. */
    public synchronized Turn askWithEvidence(String input,WikipediaResearch.Evidence evidence,Consumer<String> partial){
        return measuredAsk(input,partial,Objects.requireNonNull(evidence));
    }
    private Turn measuredAsk(String input,Consumer<String> partial,WikipediaResearch.Evidence suppliedEvidence){
        var scope=TurnTelemetry.start();
        TurnBudget budget=null;
        try{
            budget=new TurnBudget(Integer.getInteger("ivorama.llm.turnOutput",1024),java.time.Duration.ofSeconds(Integer.getInteger("ivorama.llm.turnTimeout",300)));
            var result=askInternal(input,partial,suppliedEvidence);
            return new Turn(result.generation(),result.research(),result.citationAudit(),result.totalNanos(),
                    result.interpretation(),result.verification(),result.formal(),scope.snapshot());
        }finally{lastTelemetry=scope.snapshot();scope.close();if(budget!=null)budget.close();}
    }
    private Turn askInternal(String input,Consumer<String> partial,WikipediaResearch.Evidence suppliedEvidence){
        LocalConversation.validateInput(input);Objects.requireNonNull(partial);
        long start=System.nanoTime();
        var bridge=new FormalReasoningBridge();
        var formalResult=FormalReasoningBridge.requested(input)?Optional.of(bridge.evaluate(input)):
                automaticReasoning?bridge.tryAutomatic(input):Optional.<FormalReasoningBridge.Result>empty();
        if(formalResult.isPresent()){
            taskState.reset();
            var solved=formalResult.get();var evidence=WikipediaResearch.empty(WikipediaResearch.State.NOT_REQUESTED,"");
            var result=new GenerationBackend.Result(solved.text(),"IVORAMA_SYMBOLIC","JAVA",System.nanoTime()-start,-1,null,null);
            partial.accept(result.text());
            if(solved.receipt().route().equals("AUTOMATIC_COPULAR"))conversation.accept(input,result);
            return new Turn(result,evidence,audit(result.text(),evidence),System.nanoTime()-start,RequestPlanner.legacy(),
                    new Verification(0,new OutputVerifier.Report(List.of("SYMBOLIC_CORE"),List.of()),false),Optional.of(solved.receipt()));
        }
        var source=TextAnalysisOutput.source(input);
        var explicitRules=input.startsWith(":analyser ")?new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of()):OutputVerifier.extract(input);
        var rules=taskState.prepare(input,explicitRules);
        RequestContract contract=options.requestContract()?TurnTelemetry.phase("contract",()->
                RequestContract.capture(input,explicitRules.sentences(),explicitRules.forbidden(),explicitRules.required(),source)):null;
        boolean skipPlanner=options.adaptivePlanner()&&contract!=null&&contract.simpleRecognizedTask()&&
                (contract.task().equals("CREATION")||contract.task().equals("REFORMULATION")||source.isPresent())&&
                !WikipediaResearch.requested(input);
        var interpretation=planner==null||skipPlanner?RequestPlanner.legacy():
                TurnTelemetry.phase("interpretation",()->planner.interpret(input,conversation.history()));
        // Valid proposals reuse the extracted terms: no second query-planning call.
        // Explicit Wikipedia requests retain their established path if interpretation fails or omits research.
        var evidence=TurnTelemetry.phase("research",()->suppliedEvidence!=null?suppliedEvidence:
                interpretation.usable()&&interpretation.plan().researchNeed()!=RequestPlanner.Need.NONE
                        ?research.search(interpretation.plan().terms()):research.prepare(input));
        boolean incident=source.isEmpty() && options.requestContract() && IncidentOutput.requested(input,interpretation);
        String taskContext=taskState.context(input,rules);
        String feedback="";
        String rejectedDraft="";
        boolean fragmentRepair=false;
        for(int attempt=1;attempt<=2;attempt++){
            String instructions=fragmentRepair?TextRepair.instructions():source.isPresent()?TextAnalysisOutput.instructions():incident?IncidentOutput.instructions():"";
            String context=source.map(TextAnalysisOutput::context).orElse("");
            if(!taskContext.isEmpty())context+="\n"+taskContext;
            if(!feedback.isEmpty())context+=feedback;
            var draft=conversation.draft(input,evidence,interpretation,instructions,context,source.isPresent()||fragmentRepair||incident,
                    options.compactPrompt()?contract:null,incident?LocalConversation.PromptMode.INCIDENT:!taskContext.isEmpty()?LocalConversation.PromptMode.INHERITED_TASK:LocalConversation.PromptMode.DEFAULT,t->{});
            String text=draft.text();var structural=new ArrayList<String>();
            if(fragmentRepair)try{text=TextRepair.apply(rejectedDraft,draft.text());}
            catch(IllegalArgumentException ex){structural.add("REPAIR_STRUCTURE: "+ex.getMessage());}
            if(source.isPresent())try{text=TextAnalysisOutput.render(source.get(),draft.text());}
            catch(IllegalArgumentException ex){structural.add("ANALYSIS_STRUCTURE: "+ex.getMessage());}
            if(incident)try{text=IncidentOutput.render(input,draft.text());}
            catch(IllegalArgumentException ex){structural.add("INCIDENT_STRUCTURE: "+ex.getMessage());}
            if(taskState.unchangedRewrite(input,text))structural.add("TASK_CHANGE: effectuer réellement le changement demandé, ne pas répéter le texte précédent");
            String answerToCheck=text;
            var checked=TurnTelemetry.phase("validation",()->OutputVerifier.check(answerToCheck,rules,evidence,draft.truncated()));
            var violations=new ArrayList<>(checked.violations());violations.addAll(structural);
            var checks=new ArrayList<>(checked.checks());if(source.isPresent())checks.add("EXACT_ANALYSIS_QUOTES");
            var report=new OutputVerifier.Report(checks,violations);var verification=new Verification(attempt,report,source.isPresent());
            if(report.passed()){
                var generated=new GenerationBackend.Result(text,draft.model(),draft.finishReason(),draft.wallNanos(),draft.firstTextNanos(),draft.inputTokens(),draft.outputTokens());
                // Rejected drafts never reach the callback or the conversation memory.
                partial.accept(text);conversation.accept(input,generated);taskState.accepted(text);
                return new Turn(generated,evidence,audit(text,evidence),System.nanoTime()-start,interpretation,verification,Optional.empty());
            }
            if(attempt==2)throw new RejectedOutput(verification);
            rejectedDraft=draft.text();
            fragmentRepair=options.targetedRepair()&&TextRepair.preferRepair(rejectedDraft,violations,source.isPresent()||incident);
            if(fragmentRepair)feedback="\n"+TextRepair.context(rejectedDraft,violations);
            else feedback="\n"+fr.ivorama.cognitive.StructuredJsonWriter.json(Map.of("kind","OUTPUT_CORRECTION","violations",violations,
                    "instruction",draft.truncated()?"La sortie a dépassé le budget. Produis une réponse beaucoup plus courte et terminée (120 mots maximum hors schéma imposé). Aucun préambule, ne répète pas le brouillon.":"Régénère uniquement la réponse demandée en corrigeant les violations. Aucun préambule ni numérotation inutile. Aucun nouvel appel de recherche."));
        }
        throw new IllegalStateException("Boucle de validation invalide");
    }
    public synchronized void reset(){conversation.reset();research.reset();taskState.reset();}
}
