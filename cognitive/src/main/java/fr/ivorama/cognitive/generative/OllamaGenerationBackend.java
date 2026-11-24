package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatRequestParameters;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Real SDK streaming adapter. It never fabricates a response on transport/model failure. */
public final class OllamaGenerationBackend implements GenerationBackend {
    private final StreamingChatModel model;
    private final GenerationBackend secured;
    private final LocalLlmConfig config;
    private final boolean jsonOutput;
    private final boolean exactMessages;
    private final GenerationOptions generationOptions;
    private final dev.langchain4j.model.chat.request.ResponseFormat constrainedFormat;
    public OllamaGenerationBackend(LocalLlmConfig config){
        this(config,false);
    }
    /** JSON constrains syntax; RequestPlanner still validates fields, bounds and exact quotations. */
    public OllamaGenerationBackend(LocalLlmConfig config,boolean jsonOutput){
        this(config,jsonOutput,false);
    }
    /** Explicit immutable options for reproducible experiments, including subsequent budget adaptations. */
    public OllamaGenerationBackend(LocalLlmConfig config, boolean jsonOutput, GenerationOptions options) {
        this(config, jsonOutput, false, null, options);
    }
    public static OllamaGenerationBackend nativeText(LocalLlmConfig config, GenerationOptions options) {
        return new OllamaGenerationBackend(config, false, true, null, options);
    }
    /** Native text path preserves every caller message and imposes no JSON output. */
    public static OllamaGenerationBackend nativeText(LocalLlmConfig config){
        return new OllamaGenerationBackend(config,false,true);
    }
    /** Read-only planning uses JSON syntax and the existing strict Java action validator. */
    public static OllamaGenerationBackend observerPlan(LocalLlmConfig config) {
        String historicalMode = System.getProperty("ivorama.agent.planFormat");
        if (historicalMode != null && !historicalMode.strip().equalsIgnoreCase("OFF"))
            throw new IllegalArgumentException("ivorama.agent.planFormat : le mode expérimental SCHEMA a été retiré ; supprimer cette option");
        return new OllamaGenerationBackend(config, true);
    }
    public static OllamaGenerationBackend reasonedComment(LocalLlmConfig config) {
        return new OllamaGenerationBackend(config, true, false, ReasonedCommentFormat.format());
    }
    private OllamaGenerationBackend(LocalLlmConfig config,boolean jsonOutput,boolean exactMessages){
        this(config,jsonOutput,exactMessages,null);
    }
    private OllamaGenerationBackend(LocalLlmConfig config,boolean jsonOutput,boolean exactMessages,
                                    dev.langchain4j.model.chat.request.ResponseFormat constrainedFormat){
        this(config,jsonOutput,exactMessages,constrainedFormat,GenerationOptions.fromSystemProperties(exactMessages));
    }
    private OllamaGenerationBackend(LocalLlmConfig config,boolean jsonOutput,boolean exactMessages,
                                    dev.langchain4j.model.chat.request.ResponseFormat constrainedFormat,
                                    GenerationOptions options){
        this.generationOptions=Objects.requireNonNull(options);
        this.constrainedFormat=constrainedFormat;
        this.config=config;this.jsonOutput=jsonOutput;this.exactMessages=exactMessages;
        var builder=OllamaStreamingChatModel.builder().baseUrl(config.endpoint().toString()).modelName(config.model())
                .defaultRequestParameters(OllamaChatRequestParameters.builder().numThread(config.threads()).numGPU(config.gpuLayers()).keepAlive(300).build())
                .numCtx(config.contextTokens()).numPredict(config.outputTokens())
                .temperature(config.temperature()).truncate(false)
                .timeout(config.timeout()).logRequests(false).logResponses(false);
        if(generationOptions.seed()!=null)builder.seed(generationOptions.seed());
        if(generationOptions.thinking()!=null)builder.think(generationOptions.thinking());
        if(constrainedFormat!=null)builder.responseFormat(constrainedFormat);
        else if(jsonOutput)builder.responseFormat(dev.langchain4j.model.chat.request.ResponseFormat.JSON);
        model=builder.build();
        secured=LlmSecurityGateway.configured().wrap(this::generateBudgeted);
    }
    @Override public Result generate(List<ChatMessage> messages,Consumer<String> partial){
        return secured.generate(messages,partial);
    }
    private Result generateBudgeted(List<ChatMessage> messages,Consumer<String> partial){
        var budget=fr.ivorama.cognitive.generation.TurnBudget.current();
        if(budget==null)return generateInternal(messages,partial);
        var allocation=budget.allocate(config.outputTokens(),config.timeout());
        // Each call gets the remaining output AND wall budget. Native lease/quarantine semantics stay intact.
        var adjusted=config.withLimits(allocation.outputTokens(),allocation.timeout());
        try{
            var adapter=adjusted.equals(config)?this:new OllamaGenerationBackend(adjusted,jsonOutput,exactMessages,constrainedFormat,generationOptions);
            var result=adapter.generateInternal(messages,partial);budget.consumed(allocation,result.outputTokens());return result;
        }catch(RuntimeException | Error error){budget.consumed(allocation,null);throw error;}
    }
    private Result generateInternal(List<ChatMessage> messages,Consumer<String> partial){
        Objects.requireNonNull(partial);
        long start=System.nanoTime();
        long deadline=start+config.timeout().toNanos();
        // Fail safely before inference when the protected prompt alone cannot fit.
        var bounded=exactMessages?List.copyOf(messages):PromptBudget.prepare(messages,config);
        var first=new AtomicLong(-1);
        var done=new CompletableFuture<ChatResponse>();
        RequestAdmission.Lease lease;
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new IllegalStateException("Délai écoulé avant admission");
            lease=RequestAdmission.acquire(config.endpoint()+"/"+config.model(),java.time.Duration.ofNanos(remaining));
        }catch(InterruptedException ex){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Attente locale interrompue",ex);
        }
        // The lease MUST survive the waiting Java thread until a terminal SDK callback.
        var terminal=new AtomicBoolean();
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0){
                lease.completed(); // No native request has been dispatched.
                throw new IllegalStateException("Délai écoulé en file d'attente locale");
            }
            model.chat(bounded,new StreamingChatResponseHandler(){
                @Override public void onPartialResponse(String text){
                    if(done.isDone())return;
                    if(text!=null&&!text.isEmpty()){
                        first.compareAndSet(-1,System.nanoTime()-start);
                        try{partial.accept(text);}catch(RuntimeException ex){done.completeExceptionally(ex);}
                    }
                }
                @Override public void onCompleteResponse(ChatResponse response){
                    if(!terminal.compareAndSet(false,true))return;
                    done.complete(response);
                    lease.completed();
                }
                @Override public void onError(Throwable error){
                    if(!terminal.compareAndSet(false,true))return;
                    done.completeExceptionally(error);
                    // SDK callback does not prove that remote Ollama work has ended.
                    lease.indeterminate("transport ou modèle en erreur");
                }
            });
        }catch(RuntimeException | Error error){
            // An exception during dispatch may follow a partially sent request.
            if(terminal.compareAndSet(false,true)){
                lease.indeterminate("échec d'envoi au modèle");
                done.completeExceptionally(error);
            }
            throw error;
        }
        try {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)throw new TimeoutException("Budget total épuisé");
            var response=done.get(remaining,TimeUnit.NANOSECONDS);
            String text=response.aiMessage().text();
            if(response.aiMessage().hasToolExecutionRequests())throw new IllegalStateException("Appel d'outil non pris en charge dans ce prototype");
            var metadata=response.metadata();
            var usage=metadata==null?null:metadata.tokenUsage();
            return new Result(text,config.model(),metadata==null?"UNKNOWN":String.valueOf(metadata.finishReason()),System.nanoTime()-start,first.get(),
                    usage==null?null:usage.inputTokenCount(),usage==null?null:usage.outputTokenCount());
        }catch(InterruptedException ex){
            Thread.currentThread().interrupt();
            lease.callerTimedOut();
            done.completeExceptionally(ex);
            throw new IllegalStateException("Génération interrompue ; fin Ollama non encore confirmée",ex);
        }catch(ExecutionException ex){
            // Callback has already taken responsibility for the gate.
            throw new IllegalStateException("Échec du moteur local : "+ex.getCause().getClass().getSimpleName(),ex.getCause());
        }catch(TimeoutException ex){
            lease.callerTimedOut();
            done.completeExceptionally(ex);
            throw new IllegalStateException("Délai de génération dépassé ; travail Ollama potentiellement actif",ex);
        }
    }
}
