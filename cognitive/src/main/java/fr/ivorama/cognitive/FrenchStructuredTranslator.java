package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Analyse compositionnelle d'un fragment déclaré. Aucune réponse ni règle de domaine stockée. */
public final class FrenchStructuredTranslator {
 public record Segment(int start,int end,String source,boolean question,boolean interpreted,String detail){}
 public record Translation(Optional<Problem> problem,String json,List<Segment> segments,
                           Map<String,String> symbols,List<String> unresolved){
  public Translation{segments=List.copyOf(segments);symbols=Map.copyOf(symbols);unresolved=List.copyOf(unresolved);}
  public boolean complete(){return problem.isPresent()&&unresolved.isEmpty();}
 }
 /** Texte analysé sans question, sans but artificiel et sans ouverture FCI. */
 public record Document(String sourceText,List<EntityRef> entities,List<Predicate> predicates,List<Premise> propositions,
                        List<Segment> segments,Map<String,String> symbols,List<String> unresolved){
  public Document{entities=List.copyOf(entities);predicates=List.copyOf(predicates);propositions=List.copyOf(propositions);segments=List.copyOf(segments);symbols=Map.copyOf(symbols);unresolved=List.copyOf(unresolved);}
  public String json(){return StructuredJsonWriter.json(Map.of("schema","ivoc.text.1","sourceText",sourceText,"entities",entities.stream().map(e->Map.of("kind",e.kind(),"name",e.name())).toList(),"predicates",predicates.stream().map(p->Map.of("name",p.name(),"kinds",p.kinds())).toList(),"propositions",propositions.stream().map(p->Map.of("id",p.id(),"formula",StructuredJsonWriter.expressionValue(p.expression()),"excerpt",p.evidence().excerpt(),"source",p.evidence().source())).toList(),"segments",segments.stream().map(x->Map.of("start",x.start(),"end",x.end(),"source",x.source(),"interpreted",x.interpreted(),"detail",x.detail())).toList(),"symbols",symbols,"unresolved",unresolved));}
 }
 private record Subject(EntityRef entity,Optional<SemanticLexicon.Gender> gender){}
 private static final Set<String> RESERVED=Set.of("il","elle","ils","elles","ce","cela","ça","on","je","tu","nous","vous","qui","quoi","quel","quelle","tout","tous","aucun","personne","quelqu'un","alors","si","et","ou","mais","pas","jamais","toujours");
 private static final Set<String> PRONOUNS=Set.of("il","elle","ils","elles");
 private static final Pattern COPULA=Pattern.compile("^(.+?) (n'est[ -](?:il|elle) pas|est[ -](?:il|elle)|n'est pas|est) (.+)$");
 private final CopularPropertyLexicon propertyLexicon;
 public FrenchStructuredTranslator(){this(CopularPropertyLexicon.defaults());}
 public FrenchStructuredTranslator(CopularPropertyLexicon lexicon){propertyLexicon=Objects.requireNonNull(lexicon);}
 public Translation translate(String source){return new Parser(source,propertyLexicon).run();}
 /** Layout only: skip one explicit list marker at the start of a physical line.
  * Offsets still point into the original, unmodified source. No linguistic content is removed.
  */
 private static int contentStart(String source, int start, int end) {
  while (start < end && Character.isWhitespace(source.charAt(start))) start++;
  if (start + 1 >= end || "-*•".indexOf(source.charAt(start)) < 0
      || !horizontalSpace(source.charAt(start + 1))) return start;
  for (int i = start - 1; i >= 0 && source.charAt(i) != '\n' && source.charAt(i) != '\r'; i--) {
   if (!horizontalSpace(source.charAt(i))) return start;
  }
  int content = start + 1;
  while (content < end && horizontalSpace(source.charAt(content))) content++;
  // A bare marker, or a marker on a different line from its content, is not decoration.
  return content < end && source.charAt(content) != '\n' && source.charAt(content) != '\r'
      ? content : start;
 }
 private static boolean horizontalSpace(char c) { return c == ' ' || c == '\t'; }
 /** Used by automatic routing to require full source coverage, including list layout. */
 public static boolean isLayoutGap(String source, int start, int end) {
  return source != null && start >= 0 && end >= start && end < source.length()
      && contentStart(source, start, source.length()) == end;
 }
 public Document analyzeText(String source){return new Parser(source,propertyLexicon).document();}
 // Only the registry supplies source-aligned, independently checked candidate readings.
 Document analyzeValidatedReadings(String source,Map<Integer,String> readings){return new Parser(source,propertyLexicon).document(readings);}
 public Document readDocument(String json){
  if(json==null||json.length()>200000)throw new IllegalArgumentException("Document JSON hors budget");
  var value=MiniJson.parse(json);if(!(value instanceof Map<?,?> m)||!(m.get("sourceText") instanceof String source))throw new IllegalArgumentException("Source documentaire requise");
  var document=analyzeText(source);if(!MiniJson.parse(document.json()).equals(value))throw new IllegalArgumentException("Document JSON incohérent avec sa source");
  List<Premise> decoded=new ArrayList<>();
  for(Object raw:(List<?>)m.get("propositions")){
   var p=(Map<?,?>)raw;
   decoded.add(new Premise((String)p.get("id"),StructuredProblemJson.readExpression(p.get("formula"),document.predicates()),new Evidence(Origin.DECLARATION,(String)p.get("source"),(String)p.get("excerpt"))));
  }
  return new Document(source,document.entities(),document.predicates(),decoded,document.segments(),document.symbols(),document.unresolved());
 }

 private static String normalize(String text){return text.strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");}
 private static final class Parser {
  private final String source;private final CopularPropertyLexicon lexicon;
  private final Map<String,Subject> subjects=new LinkedHashMap<>();
  private final Map<String,String> properties=new LinkedHashMap<>();
  private final Map<String,String> symbols=new LinkedHashMap<>();
  private final List<Segment> trace=new ArrayList<>();private final List<String> errors=new ArrayList<>();
  Parser(String source,CopularPropertyLexicon lexicon){this.source=source;this.lexicon=lexicon;}
  Expression assertion(String text){
   if(text.startsWith("si ")){
    Matcher rule=Pattern.compile("^si (.+?),\\s*(?:alors\\s+)?(.+)$").matcher(text);
    if(!rule.matches())throw new IllegalArgumentException("Condition exige une virgule séparant prémisse et conséquence");
    return new Implies(clause(rule.group(1),false,0),clause(rule.group(2),false,0));
   }
   return clause(text,false,0);
  }
  Document document(){return document(Map.of());}
  Document document(Map<Integer,String> readings){
   String original=source==null?"":source;List<Premise> ps=new ArrayList<>();
   if(original.isBlank()||original.length()>8000)errors.add("Texte vide ou au-delà de 8000 caractères");
   else{
    var scan=DiscourseSegments.scan(original);errors.addAll(scan.unresolved());
    for(var span:scan.spans()){
     String raw=original.substring(span.start(),span.end());
     try{
      if(span.end()<original.length()&&original.charAt(span.end())=='?')throw new IllegalArgumentException("Interrogation citée : aucune assertion admise");
      Expression e=assertion(normalize(readings.getOrDefault(span.start(),raw)));
      ps.add(new Premise("txt_p"+ps.size(),e,new Evidence(Origin.DECLARATION,"texte:"+span.start()+":"+span.end(),raw)));
      trace.add(new Segment(span.start(),span.end(),raw,false,true,"Proposition du texte, vérité non certifiée"));
     }catch(IllegalArgumentException ex){errors.add("Passage "+span.start()+":"+span.end()+" : "+ex.getMessage());trace.add(new Segment(span.start(),span.end(),raw,false,false,ex.getMessage()));}
    }
   }
   return new Document(original,new ArrayList<>(subjects.values().stream().map(Subject::entity).toList()),properties.values().stream().map(p->new Predicate(p,List.of("entity"))).toList(),ps,trace,symbols,errors);
  }
  Translation run(){
   if(source==null||source.isBlank()||source.length()>8000)return diagnostic("Énoncé vide ou au-delà de 8000 caractères");
   List<int[]> ranges=new ArrayList<>();int begin=0;
   for(int i=0;i<source.length();i++)if(".;?!\n".indexOf(source.charAt(i))>=0){range(ranges,begin,i+1);begin=i+1;}
   range(ranges,begin,source.length());
   if(ranges.size()>32)return diagnostic("Plus de 32 segments");
   int questionCount=0;for(var r:ranges)if(source.substring(r[0],r[1]).endsWith("?"))questionCount++;
   if(questionCount!=1||ranges.isEmpty()||!source.substring(ranges.get(ranges.size()-1)[0],ranges.get(ranges.size()-1)[1]).endsWith("?"))errors.add("Une unique question finale terminée par ? est requise");
   List<Premise> premises=new ArrayList<>();Expression goal=null;
   for(int n=0;n<ranges.size();n++){
    int[] r=ranges.get(n);String raw=source.substring(r[0],r[1]);boolean question=raw.endsWith("?");
    try{
     if(raw.endsWith("!"))throw new IllegalArgumentException("Exclamation hors fragment assertif");
     String text=normalize(raw.replaceFirst("[.;?]$", ""));
     Expression expression;
     if(question){
      if(n!=ranges.size()-1||questionCount!=1)throw new IllegalArgumentException("Question intermédiaire ou multiple");
      text=text.replaceFirst("^est[ -]ce que\\s+", "");expression=clause(text,true,0);goal=expression;
     }else expression=assertion(text);
     if(!question)premises.add(new Premise("fr_p"+n,expression,new Evidence(Origin.DECLARATION,"texte-fr:"+r[0]+":"+r[1],raw)));
     trace.add(new Segment(r[0],r[1],raw,question,true,"Expression complète interprétée"));
    }catch(IllegalArgumentException ex){String issue="Segment "+(n+1)+" : "+ex.getMessage();errors.add(issue);trace.add(new Segment(r[0],r[1],raw,question,false,issue));}
   }
   if(goal==null)return diagnostic("Objectif non interprété : aucune formule substituée");
   List<Predicate> definitions=properties.values().stream().map(p->new Predicate(p,List.of("entity"))).toList();
   Problem p=new Problem(source,subjects.values().stream().map(Subject::entity).toList(),definitions,premises,goal,List.of(),errors);
   return new Translation(Optional.of(p),StructuredJsonWriter.write(p),trace,symbols,errors);
  }
  void range(List<int[]> out,int start,int end){while(end>start&&Character.isWhitespace(source.charAt(end-1)))end--;start=contentStart(source,start,end);if(start<end)out.add(new int[]{start,end});}
  Translation diagnostic(String message){
   errors.add(message);String original=source==null?"":source;
   String json=StructuredJsonWriter.json(Map.of("sourceText",original,"unresolved",List.copyOf(errors),"segments",trace.stream().map(s->Map.of("start",s.start(),"end",s.end(),"source",s.source(),"interpreted",s.interpreted(),"detail",s.detail())).toList()));
   return new Translation(Optional.empty(),json,trace,symbols,errors);
  }
  Expression clause(String text,boolean question,int depth){
   if(question)text=text.replaceFirst("^est[ -]ce que\\s+", "");
   if(depth>8)throw new IllegalArgumentException("Coordination trop profonde");
   if(text.contains(" et ")){
    String[] parts=text.split(" et ",-1);if(parts.length>8)throw new IllegalArgumentException("Plus de huit propositions coordonnées");
    // Une négation elliptique ne permet pas de choisir arbitrairement sa portée.
    boolean allComplete=Arrays.stream(parts).allMatch(p->COPULA.matcher(question?p.replaceFirst("^est[ -]ce que\\s+", ""):p).matches());
    if(allComplete){Expression result=null;for(String part:parts){Expression next=clause(part,question,depth+1);result=result==null?next:new And(result,next);}return result;}
    Matcher elliptical=Pattern.compile("^(.+?) est ((?:[\\p{L}]+ et )+[\\p{L}]+)$").matcher(text);
    if(elliptical.matches()){
     Subject subject=subject(elliptical.group(1));Expression result=null;
     for(String word:elliptical.group(2).split(" et ")){Expression next=property(subject,word,false);result=result==null?next:new And(result,next);}return result;
    }
    throw new IllegalArgumentException("Coordination elliptique ou portée non résolue");
   }
   Matcher copula=COPULA.matcher(text);if(!copula.matches())throw new IllegalArgumentException("Proposition hors fragment copulaire");
   Subject subject=subject(copula.group(1));String verb=copula.group(2),complement=copula.group(3);
   boolean inversion=verb.matches(".*[ -](?:il|elle)(?: pas)?$");
   if(inversion&&!question)throw new IllegalArgumentException("Inversion interrogative dans une prémisse");
   if(inversion&&subject.gender().isPresent()){
    boolean feminine=subject.gender().get()==SemanticLexicon.Gender.FEMININE;
    if(feminine!=verb.contains("elle"))throw new IllegalArgumentException("Pronom d'inversion incompatible avec le déterminant");
   }
   if(question && verb.startsWith("n'") && complement.matches("^(?:forcément|nécessairement) .*$"))throw new IllegalArgumentException("Portée de la négation et de la nécessité non résolue");
   if(question)complement=complement.replaceFirst("^(?:forcément|nécessairement) ","");
   return property(subject,complement,verb.startsWith("n'"));
  }
  Subject subject(String raw){
   String key=normalize(raw);if(subjects.containsKey(key))return subjects.get(key);
   Optional<SemanticLexicon.Gender> gender=Optional.empty();String body=key;
   if(key.startsWith("le ")){gender=Optional.of(SemanticLexicon.Gender.MASCULINE);body=key.substring(3);}
   else if(key.startsWith("la ")){gender=Optional.of(SemanticLexicon.Gender.FEMININE);body=key.substring(3);}
   else if(key.startsWith("l'"))body=key.substring(2);
   else if(PRONOUNS.contains(key))throw new IllegalArgumentException("Pronom « "+key+" » : antécédent non résolu ; "+(subjects.isEmpty()?"aucun référent déclaré":"référents déclarés : "+subjects.values().stream().map(x->symbols.get("entity:"+x.entity().name())).toList())+". Candidats non validés : répétez le groupe nominal.");
   else if(!key.matches("[\\p{L}][\\p{L}'-]*")||RESERVED.contains(key))throw new IllegalArgumentException("Sujet non résolu ou déterminant hors fragment");
   if(!body.matches("[\\p{L}][\\p{L}'-]*(?: [a-z][a-z0-9]*)?"))throw new IllegalArgumentException("Groupe nominal complexe non résolu");
   String[] words=body.split(" ");if(Arrays.stream(words).anyMatch(RESERVED::contains))throw new IllegalArgumentException("Mot-outil dans le sujet");
   if(words.length==2&&!(words[1].length()==1||words[1].matches(".*[0-9].*")))throw new IllegalArgumentException("Identifiant ou qualificatif nominal ambigu");
   if(subjects.size()>=32)throw new IllegalArgumentException("Plus de 32 référents");
   EntityRef entity=new EntityRef("entity","e"+subjects.size());Subject result=new Subject(entity,gender);subjects.put(key,result);symbols.put("entity:"+entity.name(),raw);return result;
  }
  Expression property(Subject subject,String raw,boolean negated){
   String complement=normalize(raw);String concept;
   Matcher nominal=Pattern.compile("^(?:un|une) ([\\p{L}][\\p{L}-]*)$").matcher(complement);
   if(nominal.matches()){
    if(RESERVED.contains(nominal.group(1)))throw new IllegalArgumentException("Classe non interprétée");concept="class:"+nominal.group(1);
   }else{
    if(!complement.matches("[\\p{L}][\\p{L}-]*")||RESERVED.contains(complement)||Set.of("probablement","peut-être","apparemment","actuellement","déjà","encore","hier","demain","bientôt","autrefois","ailleurs","ici","maintenant").contains(complement))throw new IllegalArgumentException("Complément, modalité ou portée non interprété");
    var conceptId=lexicon.resolve(complement,subject.gender());
    concept=conceptId.map(id->"property:"+id).orElse("surface:"+complement);
    // Un mot inconnu reste opaque : aucune suppression de suffixe ou antonymie devinée.
   }
   String name=properties.get(concept);if(name==null){if(properties.size()>=64)throw new IllegalArgumentException("Plus de 64 prédicats");name="p"+properties.size();properties.put(concept,name);symbols.put("predicate:"+name,concept);}
   var atom=new Atom(name,List.of(new Constant(subject.entity())));return negated?new Not(atom):atom;
  }
 }
}
