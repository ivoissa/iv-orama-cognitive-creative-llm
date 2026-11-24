package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.StructuredJsonWriter;
import java.util.*;
import java.util.regex.Pattern;

/** Session-local user task metadata, bounded and separate from validated assistant memory. */
final class TaskState {
    private OutputVerifier.Rules active=new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of());
    private String original="",lastAnswer="";
    private List<String> notions=List.of();
    private static final Pattern PAIR=Pattern.compile("(?iu)diff[ée]rence entre ([^.!?\\n]{1,100}?) et ([^.!?\\n]{1,100})[.!?]?");
    private static String directiveText(String input){
        var mask=new StringBuilder(input);
        for(var quote:OutputVerifier.quotes(input))for(int i=quote.start();i<quote.end();i++)mask.setCharAt(i,' ');
        return mask.toString().toLowerCase(Locale.ROOT);
    }
    static boolean inherits(String input){return directiveText(input).matches("(?s).*mêmes? contraintes.*");}
    OutputVerifier.Rules prepare(String input,OutputVerifier.Rules explicit){
        var rules=explicit;
        if(inherits(input)){
            var forbidden=new LinkedHashSet<>(active.forbidden());forbidden.addAll(explicit.forbidden());
            var required=new LinkedHashSet<>(active.required());required.addAll(explicit.required());
            rules=new OutputVerifier.Rules(explicit.sentences().isPresent()?explicit.sentences():active.sentences(),List.copyOf(forbidden),List.copyOf(required));
        }
        boolean followup=inherits(input)||directiveText(input).matches("(?s).*(?:ce récit|ce texte|la seconde notion|la deuxième notion|la première notion).*");
        if(!followup){
            original=input.length()<=1800?input:"";active=rules;lastAnswer="";notions=List.of();
            var match=PAIR.matcher(directiveText(input));if(match.find())notions=List.of(input.substring(match.start(1),match.end(1)).strip(),input.substring(match.start(2),match.end(2)).strip());
        }else active=rules;
        return rules;
    }
    String context(String input,OutputVerifier.Rules rules){
        String lower=directiveText(input);var values=new LinkedHashMap<String,Object>();
        values.put("kind","USER_TASK_STATE");values.put("semanticStatus","USER_INSTRUCTIONS_NOT_FACTS");
        if(inherits(input)){
            values.put("sentences",rules.sentences().isPresent()?rules.sentences().getAsInt():"UNSPECIFIED");
            values.put("forbidden",rules.forbidden());values.put("required",rules.required());
            values.put("originalRequest",original);
            values.put("previousGeneratedText",lastAnswer);
            values.put("previousTextStatus","FORMAT_CHECKED_NOT_SEMANTICALLY_CERTIFIED");
        }
        if(notions.size()==2){
            int index=lower.contains("seconde notion")||lower.contains("deuxième notion")?1:lower.contains("première notion")?0:-1;
            if(index>=0){values.put("referent",notions.get(index));values.put("sourceRequest",original);}
        }
        if(values.size()==2)return "";
        values.put("currentRequest",input);
        return StructuredJsonWriter.json(values);
    }
    boolean unchangedRewrite(String input,String output){
        return !lastAnswer.isBlank()&&directiveText(input).contains("changeant")&&
                lastAnswer.replaceAll("\\s+"," ").strip().equals(output.replaceAll("\\s+"," ").strip());
    }
    void accepted(String answer){lastAnswer=answer.length()<=12000?answer:"";}
    void reset(){active=new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of());original="";lastAnswer="";notions=List.of();}
}
