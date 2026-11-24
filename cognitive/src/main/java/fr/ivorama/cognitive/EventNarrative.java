package fr.ivorama.cognitive;
import java.util.*;

/** Représentation déclarative des rôles et événements ; indépendante des phrases d'essai. */
public final class EventNarrative {
 private EventNarrative(){}
 public record Span(int start,int end,String excerpt) {
  public Span {if(start<0||end<=start||excerpt==null||excerpt.length()!=end-start)throw new IllegalArgumentException("Extrait invalide");}
 }
 public record Event(String rule,Map<String,String> roles,Span span,OptionalLong secondsBeforeNow) {
  public Event(String rule,Map<String,String> roles,Span span){this(rule,roles,span,OptionalLong.empty());}
  public Event {roles=Map.copyOf(roles);Objects.requireNonNull(rule);Objects.requireNonNull(span);Objects.requireNonNull(secondsBeforeNow);if(secondsBeforeNow.isPresent()&&(secondsBeforeNow.getAsLong()<0||secondsBeforeNow.getAsLong()>31_536_000))throw new IllegalArgumentException("Repère temporel hors budget");}
 }
 public record Fact(String relation,List<String> arguments,boolean positive,Span span) {
  public Fact {arguments=List.copyOf(arguments);Objects.requireNonNull(span);}
 }
 /** Une case libre signifie une question de sélection, aucune case libre signifie oui/non. */
 public enum Aspect { CURRENT,STILL }
 public record Query(String relation,List<String> arguments,int answerSlot,int time,boolean necessary,Span span,Aspect aspect) {
  public Query(String relation,List<String> arguments,int answerSlot,int time,boolean necessary,Span span){this(relation,arguments,answerSlot,time,necessary,span,Aspect.CURRENT);}
  public Query {arguments=List.copyOf(arguments);Objects.requireNonNull(span);Objects.requireNonNull(aspect);}
 }
 public record Narrative(String source,Map<String,String> entities,List<String> agents,List<String> objects,
                         List<Fact> initial,List<Event> events,Query query) {
  public Narrative {
   entities=Collections.unmodifiableMap(new LinkedHashMap<>(entities));agents=List.copyOf(agents);objects=List.copyOf(objects);
   initial=List.copyOf(initial);events=List.copyOf(events);Objects.requireNonNull(query);
   if(source==null||source.length()>8000||events.size()>6||agents.size()>4||objects.size()>4)throw new IllegalArgumentException("Budget narratif : 8000 caractères, 6 événements, 4 agents, 4 objets");
   if(query.time()<0||query.time()>events.size())throw new IllegalArgumentException("Instant hors récit");
   long previous=Long.MAX_VALUE;for(var e:events)if(e.secondsBeforeNow().isPresent()){long now=e.secondsBeforeNow().getAsLong();if(now>previous)throw new IllegalArgumentException("Repères temporels contraires à l'ordre des événements");previous=now;}
   if(query.aspect()==Aspect.STILL&&(query.time()==0||query.answerSlot()!=-1))throw new IllegalArgumentException("Encore exige un historique et une question oui/non");
   List<Span> spans=new ArrayList<>();initial.forEach(f->spans.add(f.span()));events.forEach(e->spans.add(e.span()));spans.add(query.span());
   for(var s:spans)if(s.end()>source.length()||!source.substring(s.start(),s.end()).equals(s.excerpt()))throw new IllegalArgumentException("Provenance incorrecte");
  }
 }
 /** Assertions/events without a query: text analysis must not invent a Question act. */
 public record Document(String source,Map<String,String> entities,List<String> agents,List<String> objects,List<Fact> initial,List<Event> events){
  public Document{
   entities=Collections.unmodifiableMap(new LinkedHashMap<>(entities));agents=List.copyOf(agents);objects=List.copyOf(objects);initial=List.copyOf(initial);events=List.copyOf(events);
   if(source==null||source.length()>8000||events.size()>6||agents.size()>4||objects.size()>4)throw new IllegalArgumentException("Budget documentaire événementiel dépassé");
   long previous=Long.MAX_VALUE;for(var event:events)if(event.secondsBeforeNow().isPresent()){long anchor=event.secondsBeforeNow().getAsLong();if(anchor>previous)throw new IllegalArgumentException("Repères temporels contraires à l'ordre des événements");previous=anchor;}
   var spans=new ArrayList<Span>();initial.forEach(f->spans.add(f.span()));events.forEach(e->spans.add(e.span()));
   for(var span:spans)if(span.end()>source.length()||!source.substring(span.start(),span.end()).equals(span.excerpt()))throw new IllegalArgumentException("Provenance événementielle incorrecte");
  }
 }
 public static String write(Narrative n){
  boolean v2=n.query().aspect()!=Aspect.CURRENT||n.events().stream().anyMatch(e->e.secondsBeforeNow().isPresent());
  List<Map<String,Object>> events=new ArrayList<>();
  for(var e:n.events()){Map<String,Object> value=new LinkedHashMap<>();value.put("rule",e.rule());value.put("roles",e.roles());value.put("span",span(e.span()));
   if(v2)value.put("time",e.secondsBeforeNow().isPresent()?Map.of("secondsBeforeNow",Math.toIntExact(e.secondsBeforeNow().getAsLong())):Map.of());events.add(value);}
  Map<String,Object> query=new LinkedHashMap<>(Map.of("relation",n.query().relation(),"arguments",n.query().arguments(),"answerSlot",n.query().answerSlot(),"time",n.query().time(),"necessary",n.query().necessary(),"span",span(n.query().span())));
  if(v2)query.put("aspect",n.query().aspect().name());
  return StructuredJsonWriter.json(Map.of("schema",v2?"ivoc.events.2":"ivoc.events.1","source",n.source(),"entities",n.entities(),"agents",n.agents(),"objects",n.objects(),
   "initial",n.initial().stream().map(f->Map.of("relation",f.relation(),"arguments",f.arguments(),"positive",f.positive(),"span",span(f.span()))).toList(),"events",events,"query",query));
 }
 private static Map<String,Object> span(Span s){return Map.of("start",s.start(),"end",s.end(),"excerpt",s.excerpt());}
 public static Narrative read(String json){
  Map<String,Object> m=obj(MiniJson.parse(json),Set.of("schema","source","entities","agents","objects","initial","events","query"));
  String schema=str(m.get("schema"));boolean v2=schema.equals("ivoc.events.2");
  if(!v2&&!schema.equals("ivoc.events.1"))throw new IllegalArgumentException("Schéma événement inconnu");
  Map<String,String> entities=strmap(m.get("entities"));List<Fact> facts=new ArrayList<>();List<Event> events=new ArrayList<>();
  for(Object x:list(m.get("initial"))){var f=obj(x,Set.of("relation","arguments","positive","span"));facts.add(new Fact(str(f.get("relation")),strings(f.get("arguments")),bool(f.get("positive")),readSpan(f.get("span"))));}
  for(Object x:list(m.get("events"))){var e=obj(x,v2?Set.of("rule","roles","span","time"):Set.of("rule","roles","span"));OptionalLong time=OptionalLong.empty();
   if(v2){if(!(e.get("time") instanceof Map<?,?> anchor))throw new IllegalArgumentException("Repère objet requis");if(!anchor.isEmpty()){var a=obj(anchor,Set.of("secondsBeforeNow"));time=OptionalLong.of(longInteger(a.get("secondsBeforeNow")));}}
   events.add(new Event(str(e.get("rule")),strmap(e.get("roles")),readSpan(e.get("span")),time));}
  var q=obj(m.get("query"),v2?Set.of("relation","arguments","answerSlot","time","necessary","span","aspect"):Set.of("relation","arguments","answerSlot","time","necessary","span"));
  return new Narrative(str(m.get("source")),entities,strings(m.get("agents")),strings(m.get("objects")),facts,events,
   new Query(str(q.get("relation")),strings(q.get("arguments")),integer(q.get("answerSlot")),integer(q.get("time")),bool(q.get("necessary")),readSpan(q.get("span")),v2?Aspect.valueOf(str(q.get("aspect"))):Aspect.CURRENT));
 }
 private static Span readSpan(Object x){var s=obj(x,Set.of("start","end","excerpt"));return new Span(integer(s.get("start")),integer(s.get("end")),str(s.get("excerpt")));}
 @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object x,Set<String> keys){if(!(x instanceof Map<?,?> m)||!m.keySet().equals(keys))throw new IllegalArgumentException("Champs JSON événement invalides");return (Map<String,Object>)m;}
 private static List<?> list(Object x){if(!(x instanceof List<?> l))throw new IllegalArgumentException("Liste requise");return l;}
 private static List<String> strings(Object x){return list(x).stream().map(EventNarrative::str).toList();}
 private static String str(Object x){if(!(x instanceof String s))throw new IllegalArgumentException("Chaîne requise");return s;}
 private static boolean bool(Object x){if(!(x instanceof Boolean b))throw new IllegalArgumentException("Booléen requis");return b;}
 private static long longInteger(Object x){if(!(x instanceof Number n)||n.doubleValue()!=Math.rint(n.doubleValue())||Math.abs(n.doubleValue())>31_536_000)throw new IllegalArgumentException("Durée entière bornée requise");return n.longValue();}
 private static int integer(Object x){if(!(x instanceof Number n)||n.doubleValue()!=Math.rint(n.doubleValue())||Math.abs(n.doubleValue())>10000)throw new IllegalArgumentException("Entier borné requis");return n.intValue();}
 private static Map<String,String> strmap(Object x){if(!(x instanceof Map<?,?> m))throw new IllegalArgumentException("Objet requis");Map<String,String> r=new LinkedHashMap<>();m.forEach((k,v)->r.put(str(k),str(v)));return r;}
}
