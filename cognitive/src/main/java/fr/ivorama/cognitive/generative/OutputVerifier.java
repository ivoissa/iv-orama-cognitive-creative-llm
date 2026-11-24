package fr.ivorama.cognitive.generative;

import java.text.*;
import java.util.*;
import java.util.regex.*;

/** Literal, measurable constraints only. This class does not certify semantic correctness. */
public final class OutputVerifier {
    public record Quote(int start,int end,String text) { }
    public record Rules(OptionalInt sentences,List<String> forbidden,List<String> required) {
        public Rules {
            Objects.requireNonNull(sentences);forbidden=List.copyOf(forbidden);required=List.copyOf(required);
            if(sentences.isPresent()&&(sentences.getAsInt()<1||sentences.getAsInt()>100))throw new IllegalArgumentException("Nombre de phrases hors budget 1..100");
            for(var terms:List.of(forbidden,required))if(terms.size()>20||terms.stream().anyMatch(t->t==null||t.isBlank()||t.length()>80))throw new IllegalArgumentException("Termes hors budget");
            var needed=new HashSet<>(required.stream().map(OutputVerifier::normal).toList());
            if(forbidden.stream().map(OutputVerifier::normal).anyMatch(needed::contains))throw new IllegalArgumentException("Terme à la fois obligatoire et interdit");
        }
    }
    public record Report(List<String> checks,List<String> violations) {
        public Report { checks=List.copyOf(checks);violations=List.copyOf(violations); }
        public boolean passed(){return violations.isEmpty();}
    }
    private static final List<String> NUMBERS=List.of("zéro","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix","onze","douze","treize","quatorze","quinze","seize","dix-sept","dix-huit","dix-neuf","vingt");
    private static final Pattern SENTENCES=Pattern.compile("(?iu)(?<![\\p{L}\\p{N}-])([0-9]+|une?|deux|trois|quatre|cinq|six|sept|huit|neuf|dix(?:-sept|-huit|-neuf)?|onze|douze|treize|quatorze|quinze|seize|vingt)\\s+phrases?(?![\\p{L}])");
    private static final Pattern TERMS=Pattern.compile("(?iu)(sans\\s+(?:(?:employer|utiliser)\\s+)?(?:les?\\s+)?(?:mots?|termes?)|(?:inclu(?:s|re|ez)|inclus|utilise[rz]?|emploie[rz]?)\\s+(?:les?\\s+)?(?:mots?|termes?))\\b");
    private OutputVerifier(){}
    public static List<Quote> quotes(String text){
        var result=new ArrayList<Quote>();
        for(int i=0;i<text.length();i++){
            char open=text.charAt(i);char close=switch(open){case '«'->'»';case '“'->'”';case '"'->'"';default->0;};
            if(close==0)continue;int j=i+1;
            for(;j<text.length();j++)if(text.charAt(j)==close&&(j==0||text.charAt(j-1)!='\\'))break;
            if(j==text.length())throw new IllegalArgumentException("Guillemet non fermé : préciser les limites du texte cité");
            result.add(new Quote(i,j+1,text.substring(i+1,j).strip()));i=j;
        }
        return List.copyOf(result);
    }
    /** Supported surface grammar is deliberately narrow; quoted payloads are never executed as requests. */
    public static Rules extract(String input){
        if(fr.ivorama.cognitive.DirectiveActs.classify(input).isEmpty())return new Rules(OptionalInt.empty(),List.of(),List.of());
        var quotes=quotes(input);var mask=new StringBuilder(input);
        for(var quote:quotes)for(int i=quote.start();i<quote.end();i++)mask.setCharAt(i,' ');
        var counts=new TreeSet<Integer>();var numbers=SENTENCES.matcher(mask);
        while(numbers.find()){
            String before=mask.substring(Math.max(0,numbers.start()-20),numbers.start()).toLowerCase(Locale.ROOT);
            String after=mask.substring(numbers.end(),Math.min(mask.length(),numbers.end()+20)).toLowerCase(Locale.ROOT);
            if(before.matches("(?s).*(?:pas|au moins|au plus|au minimum|au maximum|moins de|plus de|et|ou|à)\\s+$")||after.matches("(?s)^\\s*(?:maximum|minimum|au moins|au plus|ou moins|ou plus)\\b.*"))throw new IllegalArgumentException("Seul un nombre exact de phrases est contrôlé dans ce fragment");
            String word=numbers.group(1).toLowerCase(Locale.ROOT);int n;
            try{n=word.matches("[0-9]+")?Integer.parseInt(word):word.equals("une")?1:NUMBERS.indexOf(word);}catch(NumberFormatException e){throw new IllegalArgumentException("Nombre de phrases hors budget");}
            counts.add(n);
        }
        if(counts.size()>1)throw new IllegalArgumentException("Nombres de phrases incompatibles");
        var forbidden=new ArrayList<String>();var required=new ArrayList<String>();var markers=TERMS.matcher(mask);
        record Marker(int start,int end,boolean forbidden){}
        var found=new ArrayList<Marker>();while(markers.find())found.add(new Marker(markers.start(),markers.end(),markers.group(1).toLowerCase(Locale.ROOT).startsWith("sans")));
        for(int n=0;n<found.size();n++){
            var marker=found.get(n);int end=n+1<found.size()?found.get(n+1).start():input.length();
            for(int i=marker.end();i<end;i++)if(".;!?\n".indexOf(mask.charAt(i))>=0){end=i;break;}
            var selected=new ArrayList<String>();
            for(var quote:quotes)if(quote.start()>=marker.end()&&quote.end()<=end)selected.add(quote.text());
            if(selected.isEmpty())throw new IllegalArgumentException("Les termes à contrôler doivent être cités entre guillemets");
            (marker.forbidden()?forbidden:required).addAll(selected);
        }
        return new Rules(counts.isEmpty()?OptionalInt.empty():OptionalInt.of(counts.first()),forbidden,required);
    }
    public static int sentenceCount(String text){
        BreakIterator iterator=BreakIterator.getSentenceInstance(Locale.FRENCH);String body=text.replaceAll("(?m)^[ \t]*[0-9]{1,3}[.)][ \t]+", "");
        iterator.setText(body);int count=0;
        for(int a=iterator.first(),b=iterator.next();b!=BreakIterator.DONE;a=b,b=iterator.next())if(body.substring(a,b).codePoints().anyMatch(Character::isLetterOrDigit))count++;
        return count;
    }
    private static String normal(String text){return Normalizer.normalize(text,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    // Text and terms are normalized exactly as before. For a fixed literal, String.indexOf
    // avoids a new regex compilation/engine pass for each output constraint. Boundary
    // classification matches Java's Unicode regex [\p{L}\p{N}_] (not merely isDigit).
    private static boolean boundaryWord(int cp){
        if(cp=='_'||Character.isLetter(cp))return true;
        return switch(Character.getType(cp)){
            case Character.DECIMAL_DIGIT_NUMBER,Character.LETTER_NUMBER,Character.OTHER_NUMBER->true;
            default->false;
        };
    }
    private static boolean containsNormalized(String normalizedText,String term){
        String needle=normal(term);
        // Java's regex engine matches Unicode code points rather than UTF-16 halves.
        // Preserve legacy behavior for supplementary or malformed-surrogate literals.
        for(int i=0;i<needle.length();i++)if(Character.isSurrogate(needle.charAt(i))){
            return Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(needle)+"(?![\\p{L}\\p{N}_])")
                    .matcher(normalizedText).find();
        }
        int from=0;
        while(true){
            int at=normalizedText.indexOf(needle,from);
            if(at<0)return false;
            int end=at+needle.length();
            // Legacy Java regex lookarounds may treat a supplementary character
            // adjacent to the literal differently from codePointBefore/At.
            if((at>0&&Character.isSurrogate(normalizedText.charAt(at-1)))||
                    (end<normalizedText.length()&&Character.isSurrogate(normalizedText.charAt(end)))){
                return Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(needle)+"(?![\\p{L}\\p{N}_])")
                        .matcher(normalizedText).find();
            }
            if((at==0||!boundaryWord(normalizedText.codePointBefore(at)))&&
                    (end==normalizedText.length()||!boundaryWord(normalizedText.codePointAt(end))))return true;
            from=at+1;
        }
    }
    public static Report check(String text,Rules rules,WikipediaResearch.Evidence evidence,boolean truncated){
        var checks=new ArrayList<String>();var violations=new ArrayList<String>();checks.add("NONEMPTY");if(text.isBlank())violations.add("EMPTY_OUTPUT: réponse vide");checks.add("TRUNCATION");if(truncated)violations.add("TRUNCATED: réponse interrompue, produire une réponse plus courte et terminée");
        if(rules.sentences().isPresent()){
            checks.add("SENTENCE_COUNT");int actual=sentenceCount(text),expected=rules.sentences().getAsInt();
            if(actual!=expected)violations.add("SENTENCE_COUNT: attendu "+expected+", observé "+actual+" ; produire uniquement le texte demandé, sans préambule ni numérotation");
        }
        // One normalization replaces up to forty identical normalizations of the answer.
        String normalizedText=rules.forbidden().isEmpty()&&rules.required().isEmpty()?null:normal(text);
        for(String term:rules.forbidden()){checks.add("FORBIDDEN_TERM");if(containsNormalized(normalizedText,term))violations.add("FORBIDDEN_TERM: "+term);}
        for(String term:rules.required()){checks.add("REQUIRED_TERM");if(!containsNormalized(normalizedText,term))violations.add("REQUIRED_TERM: "+term);}
        checks.add("CITATION_IDS");var audit=GroundedConversation.audit(text,evidence);
        if(!audit.unsupportedIds().isEmpty())violations.add("CITATION_IDS: identifiants sans source reçue "+audit.unsupportedIds());
        return new Report(checks,violations);
    }
}
