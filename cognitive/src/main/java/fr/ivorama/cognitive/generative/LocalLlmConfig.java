package fr.ivorama.cognitive.generative;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Local-only prototype. No external provider, download or fallback is implicit. */
public record LocalLlmConfig(URI endpoint,String model,int contextTokens,int outputTokens,
                            int threads,int memoryMessages,Duration timeout,double temperature) {
    public LocalLlmConfig {
        Objects.requireNonNull(endpoint);Objects.requireNonNull(model);Objects.requireNonNull(timeout);
        if(!"http".equals(endpoint.getScheme()) || !Set.of("127.0.0.1","localhost","[::1]","::1").contains(endpoint.getHost())
                || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !(endpoint.getPath().isEmpty()||endpoint.getPath().equals("/"))
                || endpoint.getPort()<1 || endpoint.getPort()>65535)
            throw new IllegalArgumentException("Endpoint local HTTP avec port explicite requis");
        if(!model.matches("[a-zA-Z0-9][a-zA-Z0-9._/-]*:[a-zA-Z0-9._-]+")||model.toLowerCase(Locale.ROOT).contains("cloud"))
            throw new IllegalArgumentException("Modèle local avec tag explicite requis, pas de modèle cloud");
        if(contextTokens<512||contextTokens>16384||outputTokens<16||outputTokens>2048||outputTokens>=contextTokens
                ||threads<1||threads>64||memoryMessages<4||memoryMessages>64
                ||timeout.compareTo(Duration.ofSeconds(1))<0||timeout.compareTo(Duration.ofMinutes(10))>0
                ||!Double.isFinite(temperature)||temperature<0||temperature>2)
            throw new IllegalArgumentException("Configuration hors budgets du prototype");
    }
    public static LocalLlmConfig fromSystemProperties(){
        return new LocalLlmConfig(URI.create(System.getProperty("ivorama.llm.url","http://127.0.0.1:11434")),
                System.getProperty("ivorama.llm.model","qwen3.5:4b-q4_K_M"),integer("context",4096),integer("output",768),
                integer("threads",8),integer("memory",17),Duration.ofSeconds(integer("timeout",300)),
                Double.parseDouble(System.getProperty("ivorama.llm.temperature","0.2")));
    }
    public LocalLlmConfig withModel(String name){return new LocalLlmConfig(endpoint,name,contextTokens,outputTokens,threads,memoryMessages,timeout,temperature);}
    public int gpuLayers(){
        int value=integer("gpu",0);if(value< -1||value>999)throw new IllegalArgumentException("GPU hors budget -1..999");return value;
    }
    public LocalLlmConfig withLimits(int output,Duration limit){return new LocalLlmConfig(endpoint,model,contextTokens,output,threads,memoryMessages,limit,temperature);}
    private static int integer(String name,int fallback){return Integer.parseInt(System.getProperty("ivorama.llm."+name,""+fallback));}
}
