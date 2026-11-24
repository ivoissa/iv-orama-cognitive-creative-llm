package fr.ivorama.cognitive;

import java.util.*;
import static fr.ivorama.cognitive.FrenchNlpReader.*;

/** Partial surface AST over UD tags; all roles and reference links remain proposals. */
public final class FrenchDiscourse {
 public record Span(int start,int end,String excerpt) {}
 public record Clause(Span source,Optional<Span> subject,Optional<Span> predicate,Optional<Span> remainder,
                      String kind,List<Span> negationCues,List<String> limitations) {
  public Clause{negationCues=List.copyOf(negationCues);limitations=List.copyOf(limitations);}
 }
 public record Mention(String id,Span source,String gender,String number,boolean plainScope,String entityKey) {}
 public record Reference(Span pronoun,List<String> candidates,String status) {
  public Reference{candidates=List.copyOf(candidates);}
 }
 public record Document(String source,List<Sentence> sentences,List<Clause> clauses,List<Mention> mentions,
                        List<Reference> references,List<String> limitations) {
  public Document {sentences=List.copyOf(sentences);clauses=List.copyOf(clauses);mentions=List.copyOf(mentions);references=List.copyOf(references);limitations=List.copyOf(limitations);}
  public String json(){
   var value=new LinkedHashMap<String,Object>();
   value.put("schema","ivoc.discourse.1");value.put("reader",VERSION);value.put("sourceText",source);
   value.put("epistemicStatus","LINGUISTIC_CANDIDATES_ONLY");
   value.put("sentences",sentences.stream().map(s->Map.of("span",spanValue(span(source,s.start(),s.end())),"tokens",s.tokens().stream().map(t->Map.of("span",spanValue(span(source,t.start(),t.end())),"pos",t.pos(),"lemma",t.lemma(),"tagScoreMillionths",(int)Math.round(t.score()*1000000))).toList())).toList());
   value.put("clauses",clauses.stream().map(c->Map.of("source",spanValue(c.source()),"subject",c.subject().map(FrenchDiscourse::spanValue).orElse(Map.of()),"predicate",c.predicate().map(FrenchDiscourse::spanValue).orElse(Map.of()),"remainder",c.remainder().map(FrenchDiscourse::spanValue).orElse(Map.of()),"kind",c.kind(),"negationCues",c.negationCues().stream().map(FrenchDiscourse::spanValue).toList(),"limitations",c.limitations())).toList());
   value.put("mentions",mentions.stream().map(m->Map.of("id",m.id(),"source",spanValue(m.source()),"gender",m.gender(),"number",m.number(),"plainScope",m.plainScope(),"entityKey",m.entityKey())).toList());
   value.put("references",references.stream().map(r->Map.of("pronoun",spanValue(r.pronoun()),"candidates",r.candidates(),"status",r.status())).toList());
   value.put("limitations",limitations);return StructuredJsonWriter.json(value);
  }
  public String describe(){
   var out=new StringJoiner("\n");out.add("Lecture linguistique : propositions d'analyse, jamais des faits prouvés.");
   for(var c:clauses)out.add("- "+c.kind()+" : sujet proposé « "+c.subject().map(Span::excerpt).orElse("?")+" », prédicat « "+c.predicate().map(Span::excerpt).orElse("?")+" », suite « "+c.remainder().map(Span::excerpt).orElse("")+" »"+(c.negationCues().isEmpty()?"":" ; indices lexicaux de négation possibles, lecture et portée non certifiées")+(c.limitations().isEmpty()?"":" ; limites : "+c.limitations()));
   for(var r:references)out.add("- Pronom « "+r.pronoun().excerpt()+" » : "+r.status()+" ; antécédents candidats "+r.candidates().stream().map(id->mentions.stream().filter(m->m.id().equals(id)).findFirst().orElseThrow().source().excerpt()).toList());
   return out+"\n"+String.join(" ; ",limitations);
  }
 }
 private final FrenchNlpReader reader=new FrenchNlpReader();
 private static final Set<String> NEGATION=Set.of("ne","n'","n’","pas","jamais","plus","guère","aucun","aucune","personne","rien");
 private static final Map<String,String> GENDER=Map.of("le","M","un","M","la","F","une","F");
 private static final Map<String,String> PRONOUN_GENDER=Map.of("il","M","elle","F","ils","M","elles","F");
 private static String lower(String s){return s.toLowerCase(Locale.ROOT);}
 public Document analyze(String source){return build(source,reader.read(source));}
 public Document read(String json){
  if(json==null||json.length()>1500000)throw new IllegalArgumentException("JSON de discours hors budget");
  var raw=MiniJson.parse(json);
  if(!(raw instanceof Map<?,?> m)||!(m.get("sourceText") instanceof String s))throw new IllegalArgumentException("Source de discours requise");
  var d=analyze(s);
  if(!MiniJson.parse(d.json()).equals(raw))throw new IllegalArgumentException("Annotations incohérentes avec la source et les modèles");
  return d;
 }
 private Document build(String source,List<Sentence> sentences){
  var clauses=new ArrayList<Clause>();var mentions=new ArrayList<Mention>();var references=new ArrayList<Reference>();
  for(var sentence:sentences){
   var ts=sentence.tokens();var c=clause(source,sentence);clauses.add(c);
   // All nominal mentions are considered, including objects: never pick a subject by recency alone.
   for(int i=0;i<ts.size();i++){
    var t=ts.get(i);String word=lower(t.surface());
    if(PRONOUN_GENDER.containsKey(word)){
     String number=word.endsWith("s")?"PL":"SG";
     var candidates=mentions.stream().filter(m->m.source().end()<=t.start()&&(m.gender().equals("?")||m.gender().equals(PRONOUN_GENDER.get(word)))&&(m.number().equals("?")||m.number().equals(number))).collect(java.util.stream.Collectors.toMap(Mention::entityKey,Mention::id,(first,next)->first,LinkedHashMap::new)).values().stream().toList();
     // Unknown features or quoted/conditional scope prevent even a unique agreement proposal.
     boolean known=candidates.size()==1&&mentions.stream().filter(m->m.id().equals(candidates.getFirst())).allMatch(m->!m.gender().equals("?")&&!m.number().equals("?")&&m.plainScope());
     boolean subject=c.subject().map(s->s.start()==t.start()&&s.end()==t.end()).orElse(false);
     String status=candidates.isEmpty()?"UNRESOLVED":candidates.size()>1?"AMBIGUOUS":known&&subject&&c.limitations().isEmpty()?"UNIQUE_AGREEMENT_CANDIDATE":"UNVERIFIED_COMPATIBILITY";
     references.add(new Reference(span(source,t.start(),t.end()),candidates,status));
    }
    if(!Set.of("NOUN","PROPN").contains(t.pos()))continue;
    int first=i,last=i;
    while(first>0&&Set.of("DET","ADJ").contains(ts.get(first-1).pos()))first--;
    while(last+1<ts.size()&&Set.of("NOUN","PROPN","ADJ","NUM").contains(ts.get(last+1).pos()))last++;
    String det=lower(ts.get(first).surface());String gender=GENDER.getOrDefault(det,"?");
    String number=Set.of("les","des","ces","mes","tes","ses","nos","vos","leurs").contains(det)?"PL":GENDER.containsKey(det)?"SG":"?";
    var mentionSpan=span(source,ts.get(first).start(),ts.get(last).end());
    // Repeated definite noun phrases may corefer, but are not silently identified here.
    String id="m"+mentions.size();String tail=lower(ts.get(last).surface());
    String entityKey=tail.matches("[a-z][a-z0-9]*[0-9][a-z0-9]*")&&last>i?"explicit:"+lower(t.lemma())+":"+tail:id;
    mentions.add(new Mention(id,mentionSpan,gender,number,c.limitations().isEmpty(),entityKey));i=last;
   }
  }
  return new Document(source,sentences,clauses,mentions,references,List.of("AST de surface partiel, sans analyse de dépendances complète.","Genre des noms propres inconnu ; ni résolution sémantique, ni choix du référent le plus proche.","Les liens proposés ne sont jamais injectés comme prémisses dans le cœur logique."));
 }
 private static Clause clause(String source,Sentence sentence){
  var ts=sentence.tokens();var issues=new ArrayList<String>();var verbs=new ArrayList<Integer>();
  for(int i=0;i<ts.size();i++)if(Set.of("VERB","AUX").contains(ts.get(i).pos()))verbs.add(i);
  var cues=ts.stream().filter(t->NEGATION.contains(lower(t.surface()))).map(t->span(source,t.start(),t.end())).toList();
  String raw=source.substring(sentence.start(),sentence.end());
  if(raw.chars().anyMatch(ch->"«»“”\"()".indexOf(ch)>=0))issues.add("Citation/parenthèse : portée non résolue");
  if(ts.stream().anyMatch(t->Set.of("SCONJ","CCONJ").contains(t.pos())||Set.of("qui","que","dont","où").contains(lower(t.surface()))))issues.add("Coordination/subordination : attachement non résolu");
  if(ts.stream().anyMatch(t->t.pos().equals("PRON")&&!PRONOUN_GENDER.containsKey(lower(t.surface()))))issues.add("Pronom hors résolution locale");
  if(raw.strip().endsWith("?"))issues.add("Interrogation : aucune assertion");
  if(verbs.isEmpty())issues.add("Pas de prédicat verbal proposé");
  if(verbs.stream().filter(i->ts.get(i).pos().equals("VERB")).count()>1)issues.add("Plusieurs verbes lexicaux : portée non résolue");
  Optional<Span> subject=Optional.empty(),predicate=Optional.empty(),remainder=Optional.empty();
  if(!verbs.isEmpty()){
   int a=verbs.getFirst(),b=a;
   while(b+1<ts.size()&&(Set.of("VERB","AUX","ADV","PART").contains(ts.get(b+1).pos())))b++;
   int end=a;while(end>0&&Set.of("ADV","PART").contains(ts.get(end-1).pos()))end--;
   if(end>0&&ts.subList(0,end).stream().allMatch(t->Set.of("DET","ADJ","NOUN","PROPN","NUM","PRON").contains(t.pos()))&&ts.subList(0,end).stream().anyMatch(t->Set.of("NOUN","PROPN","PRON").contains(t.pos())))subject=Optional.of(span(source,ts.getFirst().start(),ts.get(end-1).end()));
   else issues.add("Sujet non résolu");
   predicate=Optional.of(span(source,ts.get(a).start(),ts.get(b).end()));
   int stop=ts.size();while(stop>b+1&&ts.get(stop-1).pos().equals("PUNCT"))stop--;
   if(stop>b+1)remainder=Optional.of(span(source,ts.get(b+1).start(),ts.get(stop-1).end()));
  }
  return new Clause(span(source,sentence.start(),sentence.end()),subject,predicate,remainder,issues.isEmpty()?"SIMPLE_CLAUSE_CANDIDATE":"PARTIAL_CLAUSE",cues,issues);
 }
 private static Span span(String s,int a,int b){return new Span(a,b,s.substring(a,b));}
 private static Map<String,Object> spanValue(Span s){return Map.of("start",s.start(),"end",s.end(),"excerpt",s.excerpt());}
}
