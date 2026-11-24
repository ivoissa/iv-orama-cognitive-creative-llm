package fr.ivorama.cognitive.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import fr.ivorama.cognitive.agent.ObserverSession.Operation;
import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import java.nio.file.*;import java.util.*;

/** Trusted application configuration, never derived from LLM text or documentary evidence. */
public record AgentAuthorizationPolicy(Mode mode,String readScope,Set<Operation> operations,
        boolean allowConditionalReport,boolean allowCompleteAudit,boolean requireReadBeforeFinish,
        boolean requireCompleteAudit,boolean allowPatchPreview,boolean allowIsolatedValidation) {
    public AgentAuthorizationPolicy {
        Objects.requireNonNull(mode);readScope=relative(readScope);operations=Set.copyOf(operations);
        if(requireCompleteAudit&&!allowCompleteAudit || allowIsolatedValidation&&!allowPatchPreview
                || (allowPatchPreview||allowIsolatedValidation)&&!readScope.equals("."))throw new IllegalArgumentException("Politique contradictoire");
    }
    public AgentAuthorizationPolicy(Mode mode,String readScope,Set<Operation> operations,boolean allowConditionalReport,boolean allowCompleteAudit,boolean requireReadBeforeFinish,boolean requireCompleteAudit){
        this(mode,readScope,operations,allowConditionalReport,allowCompleteAudit,requireReadBeforeFinish,requireCompleteAudit,false,false);
    }
    public static AgentAuthorizationPolicy off(){return defaults(Mode.OFF);}
    public static AgentAuthorizationPolicy defaults(Mode mode){
        return new AgentAuthorizationPolicy(mode,".",Set.of(Operation.values()),true,false,false,false);
    }
    public static AgentAuthorizationPolicy fromSystemProperties(){
        try {
            Mode mode=Mode.valueOf(System.getProperty("ivorama.agent.authorizationMode","OFF").trim().toUpperCase(Locale.ROOT));
            if(mode==Mode.OFF)return off();
            String file=System.getProperty("ivorama.agent.authorizationPolicyFile","");
            if(file.isBlank())return defaults(mode);
            byte[] bytes;try(var input=Files.newInputStream(Path.of(file))){bytes=input.readNBytes(16385);}
            if(bytes.length>16384)throw new IllegalArgumentException();
            var json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node=json.readTree(bytes);if(node==null||!node.isObject())throw new IllegalArgumentException();
            var allowed=Set.of("readScope","operations","allowConditionalReport","allowCompleteAudit","requireReadBeforeFinish","requireCompleteAudit","allowPatchPreview","allowIsolatedValidation");
            var fields=node.fieldNames();while(fields.hasNext())if(!allowed.contains(fields.next()))throw new IllegalArgumentException();
            String scope=".";if(node.has("readScope")){if(!node.path("readScope").isTextual())throw new IllegalArgumentException();scope=node.path("readScope").textValue();}
            Set<Operation> operations=EnumSet.allOf(Operation.class);
            if(node.has("operations")){
                if(!node.path("operations").isArray()||node.path("operations").size()>4)throw new IllegalArgumentException();
                operations=EnumSet.noneOf(Operation.class);for(var item:node.path("operations"))if(!item.isTextual()||!operations.add(Operation.valueOf(item.textValue())))throw new IllegalArgumentException();
            }
            return new AgentAuthorizationPolicy(mode,scope,operations,bool(node,"allowConditionalReport",true),bool(node,"allowCompleteAudit",false),bool(node,"requireReadBeforeFinish",false),bool(node,"requireCompleteAudit",false),bool(node,"allowPatchPreview",false),bool(node,"allowIsolatedValidation",false));
        }catch(Exception invalid){throw new IllegalArgumentException("Politique d'autorisation invalide");}
    }
    private static boolean bool(JsonNode node,String key,boolean fallback){
        if(!node.has(key))return fallback;if(!node.path(key).isBoolean())throw new IllegalArgumentException();return node.path(key).booleanValue();
    }
    /** Segment-based scope: src/main does not grant src/main-old; aliases are canonicalized. */
    public boolean permitsPath(String path){String normalized=relative(path);return readScope.equals(".")||normalized.equals(readScope)||normalized.startsWith(readScope+"/");}
    public static String relative(String path){
        if(path==null||path.length()>240||path.indexOf('\0')>=0)throw new IllegalArgumentException("Chemin invalide");
        String slash=path.replace('\\','/');
        if(slash.startsWith("/")||slash.startsWith("~")||slash.matches("^[A-Za-z]:.*"))throw new IllegalArgumentException("Chemin invalide");
        var segments=new ArrayList<String>();for(String segment:slash.split("/")){
            if(segment.equals("..")||segment.startsWith(".")&&!segment.equals("."))throw new IllegalArgumentException("Chemin invalide");
            if(!segment.isEmpty()&&!segment.equals("."))segments.add(segment);
        }
        return segments.isEmpty()?".":String.join("/",segments);
    }
    @Override public String toString(){return "AgentAuthorizationPolicy[mode="+mode+", scoped="+!readScope.equals(".")+"]";}
}
