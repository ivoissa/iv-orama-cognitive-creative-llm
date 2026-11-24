package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.security.LlmSecurityPolicy;
import fr.ivorama.cognitive.security.LlmSecurityPolicy.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Provider-independent gate. ENFORCE releases only the validated final text, never raw partials. */
public final class LlmSecurityGateway {
    public enum Direction { INPUT, OUTPUT }
    public record Event(Mode mode, Direction direction, Code code, long characters) { }
    public static final class Blocked extends SecurityException {
        private final Code code;
        public Blocked(Code code){super("LLM_SECURITY_BLOCKED: "+code);this.code=code;}
        public Code code(){return code;}
    }
    private final LlmSecurityPolicy policy;
    private final Consumer<Event> events;
    public LlmSecurityGateway(LlmSecurityPolicy policy,Consumer<Event> events){
        this.policy=Objects.requireNonNull(policy);this.events=Objects.requireNonNull(events);
    }
    public static LlmSecurityGateway configured(){
        return new LlmSecurityGateway(LlmSecurityPolicy.fromSystemProperties(),event->
                System.getLogger(LlmSecurityGateway.class.getName()).log(System.Logger.Level.WARNING,event.toString()));
    }
    public GenerationBackend wrap(GenerationBackend upstream) {
        Objects.requireNonNull(upstream);
        if(policy.mode()==Mode.OFF)return upstream; // Exact identity and no validation, buffering or callback replacement.
        return (messages,partial)->invoke(upstream,messages,partial);
    }
    private GenerationBackend.Result invoke(GenerationBackend upstream,List<ChatMessage> messages,Consumer<String> partial){
        Objects.requireNonNull(partial);
        List<ChatMessage> snapshot;
        try{snapshot=List.copyOf(messages);}catch(RuntimeException invalid){
            violation(Direction.INPUT,Code.UNSUPPORTED_MESSAGE,0);
            // AUDIT keeps provider behavior even for unsupported messages.
            return upstream.generate(messages,partial);
        }
        long chars=0;
        for(ChatMessage message:snapshot){
            List<String> texts=texts(message);
            if(texts==null){violation(Direction.INPUT,Code.UNSUPPORTED_MESSAGE,chars);continue;}
            for(String text:texts){
                chars+=text.length();
                if(chars>policy.maxInputChars())violation(Direction.INPUT,Code.INPUT_LIMIT,chars);
                Optional<Code> code=policy.inspect(text);
                if(code.isPresent())violation(Direction.INPUT,code.get(),chars);
            }
        }
        if(policy.mode()==Mode.AUDIT){
            var result=upstream.generate(messages,partial); // Same request, callback, result, text and stream as upstream.
            inspectOutput(result.text());return result;
        }
        AtomicLong emittedChars=new AtomicLong();
        GenerationBackend.Result result;
        try {
            result=upstream.generate(snapshot,piece->{
                if(piece!=null && emittedChars.addAndGet(piece.length())>policy.maxOutputChars())
                    throw new Blocked(Code.OUTPUT_LIMIT);
                // Do not forward any unvalidated piece. No second copy of the answer is accumulated.
            });
        }catch(Blocked blocked){violation(Direction.OUTPUT,blocked.code(),emittedChars.get());throw blocked;
        }catch(RuntimeException failed){
            violation(Direction.OUTPUT,Code.UPSTREAM_FAILURE,emittedChars.get());
            throw new Blocked(Code.UPSTREAM_FAILURE);
        }
        // A provider might swallow a callback exception. The gate still must not release its result.
        if(emittedChars.get()>policy.maxOutputChars())violation(Direction.OUTPUT,Code.OUTPUT_LIMIT,emittedChars.get());
        if(result==null)violation(Direction.OUTPUT,Code.CONTROL_FAILURE,0);
        inspectOutput(result.text());
        partial.accept(result.text());
        return result;
    }
    private void inspectOutput(String text){
        if(text.length()>policy.maxOutputChars())violation(Direction.OUTPUT,Code.OUTPUT_LIMIT,text.length());
        Optional<Code> code=policy.inspect(text);
        if(code.isPresent())violation(Direction.OUTPUT,code.get(),text.length());
    }
    private void violation(Direction direction,Code code,long characters){
        try{events.accept(new Event(policy.mode(),direction,code,characters));}
        catch(RuntimeException failed){if(policy.mode()==Mode.ENFORCE)throw new Blocked(Code.CONTROL_FAILURE);}
        if(policy.mode()==Mode.ENFORCE)throw new Blocked(code);
    }
    /** Only explicit text roles are supported in the first enforced profile. No toString()-based inspection. */
    private static List<String> texts(ChatMessage message){
        if(message instanceof SystemMessage system)return List.of(system.text());
        if(message instanceof UserMessage user){
            var text=new ArrayList<String>();
            for(var content:user.contents()){
                if(!(content instanceof TextContent value))return null;
                text.add(value.text());
            }
            return text;
        }
        if(message instanceof AiMessage ai && !ai.hasToolExecutionRequests())return List.of(ai.text()==null?"":ai.text());
        // Tool calls/results and multimodal payloads need their own typed contract, not implicit permission.
        return null;
    }
}
