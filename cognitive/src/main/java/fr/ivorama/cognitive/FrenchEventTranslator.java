package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;
import static fr.ivorama.cognitive.EventNarrative.*;

/** Grammaire bornée de rôles : lexique déclaratif séparé des règles et du solveur. */
public final class FrenchEventTranslator {
 public record Verb(String surface,String rule,List<String> roles,boolean recipientFirst,FrenchAgreement.Paradigm paradigm){
  public Verb(String surface,String rule,List<String> roles,boolean recipientFirst){this(surface,rule,roles,recipientFirst,FrenchAgreement.paradigm(surface));}
  public Verb{roles=List.copyOf(roles);Objects.requireNonNull(paradigm);if(!surface.equals(paradigm.masculineSingular()))throw new IllegalArgumentException("Base et paradigme incompatibles");}
 }
 public record Result(Optional<Narrative> narrative,String json,List<String> unresolved){public Result{unresolved=List.copyOf(unresolved);}public boolean complete(){return narrative.isPresent()&&unresolved.isEmpty();}}
 private final List<Verb> verbs;
 public FrenchEventTranslator(){this(List.of(
  new Verb("donné","transfer",List.of("source","object","target"),false),
  new Verb("remis","transfer",List.of("source","object","target"),false),
  new Verb("transmis","transfer",List.of("source","object","target"),false),
  new Verb("reçu","transfer",List.of("target","object","source"),true),
  new Verb("pris","acquire",List.of("target","object"),false),
  new Verb("ramassé","acquire",List.of("target","object"),false),
  new Verb("récupéré","acquire",List.of("target","object"),false),
  new Verb("perdu","release",List.of("source","object"),false)));
 }
 public FrenchEventTranslator(List<Verb> verbs){this.verbs=List.copyOf(verbs);}
 public Result translate(String text){return new Parser(text).run();}
 public record DocumentResult(Optional<Document> document,List<String> unresolved){public DocumentResult{unresolved=List.copyOf(unresolved);}}
 /** Selected source spans are supplied by the strict reader registry, never a synthetic question. */
 public DocumentResult readDocument(String source,List<DiscourseSegments.Span> spans){
  if(source==null||source.isBlank()||source.length()>8000||spans.isEmpty()||spans.size()>24)return new DocumentResult(Optional.empty(),List.of("Budget ou document événementiel invalide"));
  var parser=new Parser(source);
  try{
   for(var span:spans){
    String raw=source.substring(span.start(),span.end());
    if(span.end()<source.length()&&"?!".indexOf(source.charAt(span.end()))>=0)throw new IllegalArgumentException("Acte non assertif");
    var parts=new ArrayList<Part>();parser.split(parts,span.start(),span.end());
    for(var part:parts){String text=source.substring(part.start(),part.end());String clause=norm(text);
     boolean ordered=part.ordered()||clause.matches("^(?:puis|ensuite) .*");clause=clause.replaceFirst("^(?:puis|ensuite) ","");
     parser.parseStatement(clause,new Span(part.start(),part.end(),text),ordered);
    }
   }
   return new DocumentResult(Optional.of(new Document(source,parser.labels,new ArrayList<>(parser.agentsByName.values()),new ArrayList<>(parser.objectsByName.values()),parser.facts,parser.events)),List.of());
  }catch(IllegalArgumentException ex){return new DocumentResult(Optional.empty(),List.of(ex.getMessage()));}
 }
 public boolean looksNarrative(String text){
  if(text==null)return false;String n=norm(text);if(n.matches("(?s).*\\bqui (?:a|détient)\\b.*")||n.contains("détient"))return true;
  Matcher m=Pattern.compile("\\b(?:a|l'a|les a) ([\\p{L}-]+)").matcher(n);
  while(m.find())for(var v:verbs)if(!v.paradigm().analyze(m.group(1)).isEmpty())return true;return false;
 }
 private static String norm(String s){return s.strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");}
 private record Part(int start,int end,boolean ordered){}
 private final class Parser {
  final String text;final Map<String,String> labels=new LinkedHashMap<>(),agentsByName=new LinkedHashMap<>(),objectsByName=new LinkedHashMap<>();
  final Map<String,FrenchAgreement.Features> objectFeatures=new LinkedHashMap<>();
  final List<Fact> facts=new ArrayList<>();final List<Event> events=new ArrayList<>();final List<String> errors=new ArrayList<>();Query query;
  Parser(String text){this.text=text;}
  Result run(){
   if(text==null||text.isBlank()||text.length()>8000)return fail("Entrée vide ou budget de 8000 caractères dépassé");
   List<Part> parts=new ArrayList<>();int begin=0;
   for(int i=0;i<text.length();i++)if(".;?!\n".indexOf(text.charAt(i))>=0){split(parts,begin,i+1);begin=i+1;}
   split(parts,begin,text.length());if(parts.size()>24)return fail("Budget de 24 segments dépassé");
   long questions=parts.stream().filter(p->text.substring(p.start(),p.end()).endsWith("?")).count();
   if(questions!=1||parts.isEmpty()||!text.substring(parts.get(parts.size()-1).start(),parts.get(parts.size()-1).end()).endsWith("?"))errors.add("Une unique question finale est requise");
   for(int i=0;i<parts.size();i++){
    Part p=parts.get(i);Span span=new Span(p.start(),p.end(),text.substring(p.start(),p.end()));String clause=norm(span.excerpt()).replaceFirst("[.;?]$","").strip();
    boolean ordered=p.ordered()||clause.matches("^(?:puis|ensuite) .*");clause=clause.replaceFirst("^(?:puis|ensuite) ","");
    try{
     if(span.excerpt().endsWith("!"))throw new IllegalArgumentException("Exclamation non assertive");
     if(span.excerpt().endsWith("?")){if(i!=parts.size()-1||questions!=1)throw new IllegalArgumentException("Question non finale");parseQuery(clause,span);}
     else parseStatement(clause,span,ordered);
    }catch(IllegalArgumentException ex){errors.add("Segment "+(i+1)+" : "+ex.getMessage());}
   }
   if(query==null)errors.add("Objectif non interprété ; aucun objectif substitué");
   if(agentsByName.isEmpty())errors.add("Domaine des participants non renseigné : préciser le contexte");
   if(!errors.isEmpty())return fail(null);
   try{
    Narrative n=new Narrative(text,labels,new ArrayList<>(agentsByName.values()),new ArrayList<>(objectsByName.values()),facts,events,query);
    return new Result(Optional.of(n),EventNarrative.write(n),List.of());
   }catch(IllegalArgumentException ex){return fail(ex.getMessage());}
  }
  void split(List<Part> parts,int start,int end){
   while(start<end&&Character.isWhitespace(text.charAt(start)))start++;while(end>start&&Character.isWhitespace(text.charAt(end-1)))end--;if(start>=end)return;
   Matcher m=Pattern.compile(",?\\s+(?:puis|ensuite)\\s+",Pattern.CASE_INSENSITIVE).matcher(text.substring(start,end));int local=0;boolean ordered=false;
   while(m.find()){if(m.start()>local)parts.add(new Part(start+local,start+m.start(),ordered));local=m.end();ordered=true;}
   if(local<end-start)parts.add(new Part(start+local,end,ordered));
  }
  void parseStatement(String c,Span span,boolean ordered){
   OptionalLong anchor=OptionalLong.empty();
   Matcher timed=Pattern.compile("^(.+?) il y a ([\\p{L}0-9 -]+) (secondes?|minutes?|heures?|jours?)$").matcher(c);
   if(timed.matches()){anchor=OptionalLong.of(FrenchRelativeTime.seconds(timed.group(2),timed.group(3)));c=timed.group(1);}
   Matcher action=Pattern.compile("^(.+?) (l'a|les a|a) ([\\p{L}-]+)(?: (.+))?$").matcher(c);
   if(action.matches())for(Verb v:verbs){
    String token=action.group(3);if(v.paradigm().analyze(token).isEmpty())continue;
    if(!events.isEmpty()&&!ordered){var previous=events.get(events.size()-1).secondsBeforeNow();
     if(anchor.isEmpty()||previous.isEmpty()||anchor.getAsLong()>=previous.getAsLong())throw new IllegalArgumentException("Ordre entre événements non explicite : puis/ensuite ou repères temporels croissants requis");}
    String actor=agent(action.group(1)),obj,target=null,tail=action.group(4);boolean clitic=!action.group(2).equals("a");
    if(clitic){
     if(objectsByName.size()!=1)throw new IllegalArgumentException("Pronom objet ambigu ou sans antécédent unique");
     obj=objectsByName.values().iterator().next();var features=objectFeatures.get(obj).unify(new FrenchAgreement.Features(FrenchAgreement.Gender.UNKNOWN,action.group(2).equals("les a")?FrenchAgreement.GrammaticalNumber.PLURAL:FrenchAgreement.GrammaticalNumber.SINGULAR));
     objectFeatures.put(obj,FrenchAgreement.agree(v.paradigm(),token,features));
     if(v.roles().size()==3){Matcher participant=Pattern.compile("^"+(v.recipientFirst()?"de":"à")+" (.+)$").matcher(tail==null?"":tail);if(!participant.matches())throw new IllegalArgumentException("Participant destinataire/source requis");target=agent(participant.group(1));}
     else if(tail!=null)throw new IllegalArgumentException("Complément supplémentaire non interprété");
    }else{
     // Avec avoir, le COD qui suit ne déclenche pas d'accord : forme de base exigée.
     if(!token.equals(v.surface()))throw new IllegalArgumentException("Accord non autorisé avec un COD postposé sous avoir");
     if(tail==null)throw new IllegalArgumentException("Objet explicite manquant");
     if(v.roles().size()==3){Matcher complement=Pattern.compile("^(.+?) "+(v.recipientFirst()?"de":"à")+" (.+)$").matcher(tail);if(!complement.matches())throw new IllegalArgumentException("Objet et participant destinataire/source requis");obj=object(complement.group(1),true);target=agent(complement.group(2));}
     else obj=object(tail,true);
    }
    Map<String,String> roles=new LinkedHashMap<>();roles.put(v.roles().get(0),actor);roles.put(v.roles().get(1),obj);if(target!=null)roles.put(v.roles().get(2),target);
    events.add(new Event(v.rule(),roles,span,anchor));return;
   }
   if(anchor.isPresent())throw new IllegalArgumentException("Repère compris, mais événement associé non traduit");
   Matcher fact=Pattern.compile("^(.+?) (n'a pas|ne détient pas|a|détient) (.+)$").matcher(c);
   if(fact.matches()){
    if(!events.isEmpty())throw new IllegalArgumentException("Observation après événement non prise en charge");
    facts.add(new Fact("holds",List.of(agent(fact.group(1)),object(fact.group(3),true)),!fact.group(2).startsWith("n"),span));return;
   }
   throw new IllegalArgumentException("Action ou construction hors grammaire ; aucun fragment ignoré");
  }
  void parseQuery(String c,Span span){
   int time=events.size();if(c.endsWith(" au début")){time=0;c=c.substring(0,c.length()-9);}else c=c.replaceFirst(" (?:maintenant|à la fin)$","");
   boolean necessary=c.contains("forcément")||c.contains("nécessairement");
   Matcher who=Pattern.compile("^qui (?:a|détient) (.+)$").matcher(c);
   if(who.matches()){query=new Query("holds",List.of("?",object(who.group(1),false)),0,time,necessary,span);return;}
   c=c.replaceFirst("^est-ce que ","");
   Matcher yes=Pattern.compile("^(.+?) (?:a-t-il|a-t-elle|a|détient-il|détient-elle|détient) (?:(forcément|nécessairement|encore) )?(.+)$").matcher(c);
   if(!yes.matches())throw new IllegalArgumentException("Question non prise en charge (sélection ou détention oui/non)");
   query=new Query("holds",List.of(agent(yes.group(1)),object(yes.group(3),false)),-1,time,necessary,span,"encore".equals(yes.group(2))?Aspect.STILL:Aspect.CURRENT);
  }
  String agent(String raw){
   String key=norm(raw);if(!key.matches("(?:le |la |l')?[\\p{L}][\\p{L}-]*(?: [a-z][0-9]+)?")||Set.of("il","elle","je","tu","on","nous","vous","qui","personne","quelqu'un","tout","tous","aucun","un","une","ce","cela").contains(key))throw new IllegalArgumentException("Agent complexe ou pronom non résolu");
   return agentsByName.computeIfAbsent(key,k->{String id="e"+labels.size();labels.put(id,raw);return id;});
  }
  String object(String raw,boolean introduction){
   String key=norm(raw);Matcher m=Pattern.compile("^(le |la |l'|un |une |les |des )([\\p{L}][\\p{L}-]*(?: [a-z][0-9]+)?)$").matcher(key);
   if(!m.matches())throw new IllegalArgumentException("Objet, possessif ou complément non résolu");String noun=m.group(2);
   var features=FrenchAgreement.determiner(m.group(1));
   boolean indefinite=m.group(1).startsWith("un")||m.group(1).equals("des ");if(indefinite&&(!introduction||objectsByName.containsKey(noun)))throw new IllegalArgumentException("Référent indéfini nouveau ou ambigu");
   if(objectsByName.containsKey(noun)){String id=objectsByName.get(noun);objectFeatures.put(id,objectFeatures.get(id).unify(features));return id;}
   // Un référent mentionné dans la seule question reste inconnu, jamais présumé détenu.
   String id="e"+labels.size();labels.put(id,raw);objectsByName.put(noun,id);objectFeatures.put(id,features);return id;
  }
  Result fail(String message){if(message!=null)errors.add(message);return new Result(Optional.empty(),StructuredJsonWriter.json(Map.of("schema","ivoc.events.1.diagnostic","source",text==null?"":text,"unresolved",List.copyOf(errors))),errors);}
 }
}
