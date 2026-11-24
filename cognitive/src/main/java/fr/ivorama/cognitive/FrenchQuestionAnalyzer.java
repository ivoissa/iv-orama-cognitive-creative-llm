package fr.ivorama.cognitive;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;

/** Analyse de candidats sémantiques, sans création de faits ou de concepts. */
public final class FrenchQuestionAnalyzer {
    public enum Kind { CLASSIFICATION, PROPERTY, INDIVIDUAL_STATE, EXPLANATION, DATED_VALUE, CONTEXTUAL_VISIBILITY, UNSUPPORTED }
    public enum Need { LOCATION, DATE, ENTITY, OBSERVATION, INTENT }
    public enum Scope { PUBLIC_DATED_VALUE, INDIVIDUAL_OBSERVATION }
    public record Analysis(String original,String canonical,Kind kind,String subject,String relation,
                           String target,Optional<LocalDate> date,Optional<String> location,
                           Set<Need> missing,boolean ontologyCompatible,Optional<ContextualVisibilityReasoner.Frame> context) {
        public Analysis(String original,String canonical,Kind kind,String subject,String relation,String target,
                        Optional<LocalDate> date,Optional<String> location,Set<Need> missing,boolean ontologyCompatible){
            this(original,canonical,kind,subject,relation,target,date,location,missing,ontologyCompatible,Optional.empty());
        }
        public Analysis { date=Objects.requireNonNull(date);location=Objects.requireNonNull(location);missing=Set.copyOf(missing);context=Objects.requireNonNull(context); }
        Analysis withLocation(String place){
            Set<Need> needs=new HashSet<>(missing);needs.remove(Need.LOCATION);
            return new Analysis(original,canonical,kind,subject,relation,target,date,Optional.of(place),needs,ontologyCompatible,context);
        }
        Analysis withDate(LocalDate day){
            Set<Need> needs=new HashSet<>(missing);needs.remove(Need.DATE);
            return new Analysis(original,canonical,kind,subject,relation,target,Optional.of(day),location,needs,ontologyCompatible,context);
        }
    }
    private final Clock clock;
    private final Map<String,Scope> lexicon;
    private final FrenchOntologicalQuestionParser ontology=new FrenchOntologicalQuestionParser();
    public FrenchQuestionAnalyzer(Clock clock){this(clock,defaultLexicon());}
    /** Le lexique associe un mot à un rôle, jamais une question à une réponse. */
    public FrenchQuestionAnalyzer(Clock clock,Map<String,Scope> lexicon){
        this.clock=Objects.requireNonNull(clock);
        Map<String,Scope> normalized=new HashMap<>();
        lexicon.forEach((word,scope)->normalized.put(normalize(word),Objects.requireNonNull(scope)));
        this.lexicon=Map.copyOf(normalized);
    }
    public static Map<String,Scope> defaultLexicon(){
        Map<String,Scope> words=new HashMap<>();
        for(String w:List.of("météo","température","précipitations","humidité","vent"))words.put(w,Scope.PUBLIC_DATED_VALUE);
        for(String w:List.of("faim","soif","fatigué","malade","ouvert","disponible","allumé","connecté"))words.put(w,Scope.INDIVIDUAL_OBSERVATION);
        return Map.copyOf(words);
    }
    public static String normalize(String text){return OntologicalKnowledgeGraph.normalized(text);}
    public static String canonical(String input){
        return normalize(input).replaceAll("[?.!]+$", "").strip()
            .replaceAll("\\best[ -]+(?:il|elle|ils|elles)\\b", "est")
            .replaceAll("\\ba[ -]+(?:t[ -]+)?(?:il|elle|ils|elles)\\b", "a")
            .replaceFirst("^est[ -]ce que\\s+", "");
    }
    public Analysis analyze(String original){
        if(original==null || original.isBlank() || original.length()>2000)throw new IllegalArgumentException("Question vide ou trop longue");
        String text=canonical(original);
        var contextual=new ContextualVisibilityReasoner().analyze(original);
        if(contextual.isPresent())return new Analysis(original,contextual.get().question(),Kind.CONTEXTUAL_VISIBILITY,
            "je","direct_visibility",contextual.get().target(),Optional.empty(),Optional.empty(),Set.of(),false,contextual);
        // Ne jamais avaler une scène ou une seconde proposition dans un terme lexical.
        if(text.matches(".*[;.,:?!].*") || text.matches(".*\\b(?:est[ -]ce que|je vois)\\b.*"))return unsupported(original,text);
        if(text.equals("pourquoi") || text.equals("et pourquoi"))return frame(original,text,Kind.EXPLANATION,"","justify","",true);
        boolean why=text.startsWith("pourquoi ");
        String body=why?text.substring(9).strip():text;
        // Le fragment ne résout pas les portées de négation, condition, coordination ou modalité.
        if(Pattern.compile("\\b(?:pas|jamais|peut|pourrait|serait|si|ou|et)\\b").matcher(body).find())return unsupported(original,text);
        Optional<Analysis> dated=dated(original,text,body);
        if(dated.isPresent())return dated.get();
        var relation=ontology.surface(body);
        if(relation.isPresent()){
            var q=relation.get();
            return frame(original,why?"Pourquoi "+body:body,why?Kind.EXPLANATION:Kind.CLASSIFICATION,
                    q.subject(),"is_a",q.target(),true);
        }
        Matcher state=Pattern.compile("^(.+?) (est|a) (.+)$").matcher(body);
        if(state.matches()){
            String subject=stripArticle(state.group(1)),target=state.group(3);
            if(subject.isBlank() || subject.matches(".*\\b(?:quel|quelle|qui|comment|pourquoi)\\b.*"))return unsupported(original,text);
            Scope scope=lexicon.get(target);
            if(scope==Scope.INDIVIDUAL_OBSERVATION)
                return new Analysis(original,text,Kind.INDIVIDUAL_STATE,subject,state.group(2),target,
                        Optional.empty(),Optional.empty(),Set.of(Need.ENTITY,Need.OBSERVATION),false);
            if(state.group(2).equals("est"))return frame(original,text,why?Kind.EXPLANATION:Kind.PROPERTY,subject,"has_property",target,false);
        }
        return unsupported(original,text);
    }
    private Optional<Analysis> dated(String original,String canonical,String body){
        Matcher m=Pattern.compile("^(?:quel|quelle|quels|quelles) (?:est|etait|sera|sont|etaient) (?:le |la |les |l')?(.+)$").matcher(body);
        if(!m.matches())return Optional.empty();
        String rest=m.group(1),dimension=null;
        for(String word:lexicon.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()){
            if(lexicon.get(word)==Scope.PUBLIC_DATED_VALUE && (rest.equals(word)||rest.startsWith(word+" "))){dimension=word;rest=rest.substring(word.length()).strip();break;}
        }
        if(dimension==null)return Optional.empty();
        int dateMarkers=0;
        Matcher markers=Pattern.compile("(?<![a-z0-9])(?:aujourd'hui|hier|demain|\\d{4}-\\d{2}-\\d{2})(?![a-z0-9])").matcher(rest);
        while(markers.find())dateMarkers++;
        if(dateMarkers>1)return Optional.of(unsupported(original,canonical));
        Optional<LocalDate> date=Optional.empty();
        for(String word:List.of("aujourd'hui","hier","demain")){
            if(rest.matches(".*(?<![a-z])"+Pattern.quote(word)+"(?![a-z]).*")){
                date=Optional.of(LocalDate.now(clock).plusDays(word.equals("hier")?-1:word.equals("demain")?1:0));
                rest=rest.replace(word, "").strip();break;
            }
        }
        Matcher iso=Pattern.compile("(?<![0-9])(\\d{4}-\\d{2}-\\d{2})(?![0-9])").matcher(rest);
        if(iso.find()){
            if(date.isPresent())return Optional.of(unsupported(original,canonical));
            try{date=Optional.of(LocalDate.parse(iso.group(1)));}catch(DateTimeParseException ex){return Optional.of(unsupported(original,canonical));}
            rest=(rest.substring(0,iso.start())+rest.substring(iso.end())).strip();
        }
        Optional<String> location=Optional.empty();
        rest=rest.replaceAll("\\s+"," ").strip();
        Matcher place=Pattern.compile("^(?:a|au|aux|en) ([a-z][a-z' -]{0,79})$").matcher(rest);
        if(place.matches())location=Optional.of(place.group(1));
        else if(!rest.isEmpty())return Optional.of(unsupported(original,canonical));
        Set<Need> missing=new HashSet<>();if(date.isEmpty())missing.add(Need.DATE);if(location.isEmpty())missing.add(Need.LOCATION);
        return Optional.of(new Analysis(original,canonical,Kind.DATED_VALUE,"",dimension,"",date,location,missing,false));
    }
    private static String stripArticle(String s){return s.replaceFirst("^(?:une |un |le |la |les |l')", "").strip();}
    private static Analysis frame(String original,String canonical,Kind kind,String subject,String relation,String target,boolean ontology){
        return new Analysis(original,canonical,kind,subject,relation,target,Optional.empty(),Optional.empty(),Set.of(),ontology);
    }
    private static Analysis unsupported(String original,String canonical){return new Analysis(original,canonical,Kind.UNSUPPORTED,"","","",Optional.empty(),Optional.empty(),Set.of(Need.INTENT),false);}
}
