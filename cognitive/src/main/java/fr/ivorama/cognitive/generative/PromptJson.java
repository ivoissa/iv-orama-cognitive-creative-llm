package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Sort keys only in Java-created data envelopes; never rewrites user input or generated history. */
public final class PromptJson {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private PromptJson() { }
    private static JsonNode ordered(JsonNode n){
        if(n.isObject()){var out=JSON.createObjectNode();var names=new TreeSet<String>();n.fieldNames().forEachRemaining(names::add);for(var k:names)out.set(k,ordered(n.get(k)));return out;}
        if(n.isArray()){var out=JSON.createArrayNode();n.forEach(x->out.add(ordered(x)));return out;}return n;
    }
    public static String canonical(String text){
        if(text.length()>300000)throw new IllegalArgumentException("Contexte JSON hors budget");
        if(!text.stripLeading().startsWith("{"))throw new IllegalArgumentException("Objet de contexte requis");
        try(var values=JSON.readerFor(JsonNode.class).readValues(text)){
            var parts=new ArrayList<String>();while(values.hasNextValue())parts.add(JSON.writeValueAsString(ordered((JsonNode)values.nextValue())));
            return String.join("\n",parts);
        }catch(java.io.IOException e){throw new IllegalArgumentException("Contexte JSON invalide",e);}
    }
}
