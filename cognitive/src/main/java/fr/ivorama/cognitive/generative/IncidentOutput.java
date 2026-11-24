package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Exact user excerpts are the only observations. Hypotheses/checks remain generated, uncertified text. */
final class IncidentOutput {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static boolean requested(String input,RequestPlanner.Interpretation plan){
        if(plan.usable()&&plan.plan().tasks().contains(RequestPlanner.Task.INVESTIGATION))return true;
        // Recognition of intent only; quoted payloads cannot activate an incident mode.
        String lower=input.toLowerCase(Locale.ROOT);var text=new StringBuilder(lower);
        for(var q:OutputVerifier.quotes(input))for(int i=q.start();i<q.end();i++)text.setCharAt(i,' ');
        return text.toString().matches("(?s).*(?:diagnostique|investigue|analyse cet incident|mesures[^.!?]{0,100}(?:reprise|redémarrage)).*");
    }
    static String instructions(){return """
        Retourne uniquement {"hypotheses":["possibilité non vérifiée"],"checks":["mesure à recueillir"]}.
        Aucun champ observations : Java reprend directement le texte fourni par l'utilisateur.
        hypotheses : 0 à 3 possibilités, jamais des constats. checks : 1 à 3 vérifications discriminantes à effectuer, jamais des actions prétendument effectuées.
        Maximum 180 caractères par élément. Une absence d'information ne prouve aucune absence de phénomène.
        """;}
    static String render(String source,String output){
        try{
            var n=JSON.readTree(output);
            if(!n.isObject()||n.size()!=2||!n.has("hypotheses")||!n.has("checks"))throw new IllegalArgumentException("Schéma hypotheses/checks requis, aucun constat produit par le modèle");
            var hypotheses=read(n.get("hypotheses"),0,3);var checks=read(n.get("checks"),1,3);
            String excerpt=source.substring(0,Math.min(source.length(),1800));
            return "Demande fournie (citation exacte, non vérifiée) :\n« "+excerpt+" »"+(source.length()>1800?" [extrait limité à 1800 caractères]":"")+
                    "\nHypothèses non vérifiées :\n"+(hypotheses.isEmpty()?"Aucune proposée.":String.join("\n",hypotheses.stream().map(s->"- "+s).toList()))+
                    "\nVérifications proposées, non effectuées :\n"+String.join("\n",checks.stream().map(s->"- "+s).toList());
        }catch(java.io.IOException e){throw new IllegalArgumentException("JSON incident invalide",e);}
    }
    private static List<String> read(JsonNode n,int minimum,int maximum){
        if(!n.isArray()||n.size()<minimum||n.size()>maximum)throw new IllegalArgumentException("Liste incident hors budget");
        var values=new ArrayList<String>();for(var item:n){if(!item.isTextual()||item.textValue().isBlank()||item.textValue().length()>180)throw new IllegalArgumentException("Élément incident hors budget");values.add(item.textValue());}return values;
    }
}
