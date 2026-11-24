package fr.ivorama.cognitive.security;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Trusted startup policy. This is a bounded text/credential policy, not a semantic injection detector. */
public final class LlmSecurityPolicy {
    public enum Mode { OFF, AUDIT, ENFORCE }
    public enum Code { INPUT_LIMIT, OUTPUT_LIMIT, PROTECTED_LITERAL, PRIVATE_KEY,
        UNSUPPORTED_MESSAGE, CONTROL_FAILURE, UPSTREAM_FAILURE }
    private static final Pattern PRIVATE_KEY = Pattern.compile(
        "-----BEGIN ((?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY)-----[\\r\\n]+[A-Za-z0-9+/=\\r\\n]{64,}-----END \\1-----");
    private final Mode mode;
    private final int maxInputChars, maxOutputChars;
    private final List<String> protectedValues;
    public LlmSecurityPolicy(Mode mode, int maxInputChars, int maxOutputChars, List<String> protectedValues) {
        this.mode=Objects.requireNonNull(mode);
        if(maxInputChars<1 || maxInputChars>1_048_576 || maxOutputChars<1 || maxOutputChars>1_048_576)
            throw new IllegalArgumentException("Limites de sécurité invalides");
        this.maxInputChars=maxInputChars;this.maxOutputChars=maxOutputChars;
        this.protectedValues=List.copyOf(Objects.requireNonNull(protectedValues));
        if(this.protectedValues.size()>64 || this.protectedValues.stream().anyMatch(s->s.length()<8 || s.length()>4096))
            throw new IllegalArgumentException("Registre de valeurs protégées invalide");
    }
    public static LlmSecurityPolicy off(){return new LlmSecurityPolicy(Mode.OFF,32768,65536,List.of());}
    public static LlmSecurityPolicy fromSystemProperties() {
        try {
            Mode mode=Mode.valueOf(System.getProperty("ivorama.security.mode","OFF").trim().toUpperCase(Locale.ROOT));
            if(mode==Mode.OFF)return off(); // Disabled means no policy file access or content inspection.
            int input=Integer.parseInt(System.getProperty("ivorama.security.maxInputChars","32768"));
            int output=Integer.parseInt(System.getProperty("ivorama.security.maxOutputChars","65536"));
            String file=System.getProperty("ivorama.security.protectedValuesFile","");
            List<String> values=new ArrayList<>();
            if(!file.isBlank()) {
                byte[] bytes;
                try(var in=Files.newInputStream(Path.of(file))){bytes=in.readNBytes(32769);}
                if(bytes.length>32768)throw new IllegalArgumentException();
                JsonNode node=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
                if(node==null || !node.isArray() || node.size()>64)throw new IllegalArgumentException();
                for(JsonNode value:node){if(!value.isTextual())throw new IllegalArgumentException();values.add(value.textValue());}
            }
            return new LlmSecurityPolicy(mode,input,output,values);
        }catch(Exception invalid){
            // Jackson and NIO messages can contain secrets or paths. Never retain their cause here.
            throw new IllegalArgumentException("Configuration de sécurité invalide");
        }
    }
    public Mode mode(){return mode;}
    public int maxInputChars(){return maxInputChars;}
    public int maxOutputChars(){return maxOutputChars;}
    /** No secret text, offset or reversible fingerprint is exposed by a decision. */
    public Optional<Code> inspect(String text) {
        for(String value:protectedValues)if(text.contains(value))return Optional.of(Code.PROTECTED_LITERAL);
        return PRIVATE_KEY.matcher(text).find()?Optional.of(Code.PRIVATE_KEY):Optional.empty();
    }
    @Override public String toString(){return "LlmSecurityPolicy[mode="+mode+", protectedValueCount="+protectedValues.size()+"]";}
}
