package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Diagnostic API only; does not download, execute tools or send prompts to another provider. */
public final class OllamaProbe {
    private final LocalLlmConfig config;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public OllamaProbe(LocalLlmConfig config){this.config=config;}
    public JsonNode get(String path)throws Exception{return request(path,null);}
    private JsonNode request(String path,Object body)throws Exception{
        var builder=HttpRequest.newBuilder(config.endpoint().resolve(path)).timeout(config.timeout());
        if(body!=null)builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8));
        else builder.GET();
        var result=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(var in=result.body()){
            byte[] bytes=in.readNBytes(2_000_001);
            if(bytes.length>2_000_000)throw new IllegalStateException("Diagnostic Ollama trop volumineux");
            if(result.statusCode()!=200)throw new IllegalStateException("Diagnostic Ollama HTTP "+result.statusCode());
            var root=json.readTree(bytes);if(root==null||root.has("error"))throw new IllegalStateException("Diagnostic Ollama invalide");return root;
        }
    }
    public JsonNode requireInstalled()throws Exception{
        for(var model:get("/api/tags").path("models"))
            if(config.model().equals(model.path("name").asText())||config.model().equals(model.path("model").asText()))return model;
        throw new IllegalStateException("Modèle local absent : installer explicitement "+config.model()+" avec ollama pull");
    }
    public void unload()throws Exception{request("/api/generate",Map.of("model",config.model(),"keep_alive",0,"stream",false));}
}
