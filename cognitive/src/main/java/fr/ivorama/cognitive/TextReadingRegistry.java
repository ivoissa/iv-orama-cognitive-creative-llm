package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Strict document readers. No inferred POS tag, fabricated query or implicit cross-domain identity. */
public final class TextReadingRegistry {
 public record Reading(int start,int end,String reader,boolean accepted,String detail){}
 public record Registered(String source,List<Reading> readings,List<Predicate> predicates,List<Premise> premises,Map<String,String> symbols,List<String> issues){
  public Registered{readings=List.copyOf(readings);predicates=List.copyOf(predicates);premises=List.copyOf(premises);symbols=Map.copyOf(symbols);issues=List.copyOf(issues);}
  public boolean complete(){return issues.isEmpty()&&readings.stream().allMatch(Reading::accepted);}
  public String json(){return StructuredJsonWriter.json(Map.of("schema","ivoc.text-readers.1","sourceText",source,
   "readings",readings.stream().map(r->Map.of("start",r.start(),"end",r.end(),"reader",r.reader(),"accepted",r.accepted(),"detail",r.detail())).toList(),
   "predicates",predicates.stream().map(p->Map.of("name",p.name(),"kinds",p.kinds())).toList(),
   "premises",premises.stream().map(p->Map.of("id",p.id(),"formula",StructuredJsonWriter.expressionValue(p.expression()),"origin",p.evidence().origin().name(),"source",p.evidence().source(),"excerpt",p.evidence().excerpt())).toList(),"symbols",symbols,"issues",issues));}
 }
 public Registered read(String json){
  if(json==null||json.length()>300000)throw new IllegalArgumentException("Registre JSON hors budget");
  Object raw=MiniJson.parse(json);
  if(!(raw instanceof Map<?,?> m)||!(m.get("sourceText") instanceof String source))throw new IllegalArgumentException("Source du registre requise");
  var expected=analyze(source);
  if(!MiniJson.parse(expected.json()).equals(raw))throw new IllegalArgumentException("Registre incohérent avec la source et les grammaires");
  var decoded=new ArrayList<Premise>();
  for(Object item:(List<?>)m.get("premises")){
   var p=(Map<?,?>)item;
   decoded.add(new Premise((String)p.get("id"),StructuredProblemJson.readExpression(p.get("formula"),expected.predicates()),new Evidence(Origin.valueOf((String)p.get("origin")),(String)p.get("source"),(String)p.get("excerpt"))));
  }
  return new Registered(source,expected.readings(),expected.predicates(),decoded,expected.symbols(),expected.issues());
 }
 public Registered analyze(String source){
  var translator=new FrenchStructuredTranslator();var d=translator.readDocument(translator.analyzeText(source).json());
  var proposals=new LinkedHashMap<Integer,NlpFormalBridge.Proposal>();var bridge=new NlpFormalBridge();
  for(var segment:d.segments())if(!segment.interpreted())bridge.propose(source,segment).ifPresent(p->proposals.put(p.start(),p));
  if(!proposals.isEmpty()){
   var replacements=new LinkedHashMap<Integer,String>();proposals.forEach((start,p)->replacements.put(start,p.normalized()));
   d=translator.analyzeValidatedReadings(source,replacements);
  }
  var readings=new ArrayList<Reading>();var predicates=new ArrayList<>(d.predicates());var premises=new ArrayList<>(d.propositions());var symbols=new LinkedHashMap<>(d.symbols());
  var issues=new ArrayList<>(DiscourseSegments.scan(source).unresolved());if(source.isBlank()||source.length()>8000)issues.add("Texte vide ou hors budget");var quant=new Quantified(d,predicates,symbols);
  for(var segment:d.segments()){
   if(segment.interpreted()){
    var proposal=proposals.get(segment.start());
    readings.add(new Reading(segment.start(),segment.end(),proposal==null?"COPULA":"NLP_VALIDATED_DISTRIBUTIVE",true,proposal==null?"Grammaire copulaire complète":proposal.rule()+" ; lecture vérifiée : "+proposal.normalized()+" ; assertion du texte, aucune vérité externe certifiée"));
   }
   else {
    var parsed=quant.parse(segment.source()+".");
    if(parsed.isPresent()){
     int count=0;for(var expression:parsed.orElseThrow())premises.add(new Premise("quant_"+segment.start()+"_"+count++,expression,new Evidence(Origin.DECLARATION,"texte:"+segment.start()+":"+segment.end(),segment.source()+" [quantification sur le domaine nommé fini du document]")));
     readings.add(new Reading(segment.start(),segment.end(),"FINITE_QUANTIFIER",true,"Domaine fini nommé explicitement, pas tous les objets du monde"));
    }else readings.add(new Reading(segment.start(),segment.end(),"UNRESOLVED",false,segment.detail()));
   }
  }
  var remaining=readings.stream().filter(r->!r.accepted()).map(r->new DiscourseSegments.Span(r.start(),r.end())).toList();
  if(!remaining.isEmpty()){
   var event=new FrenchEventTranslator().readDocument(source,remaining);
   if(event.document().isPresent())try{
    var compiled=new EventLogicCompiler(EventRuleRegistry.defaults()).compileDocument(event.document().orElseThrow());
    predicates.addAll(compiled.predicates());premises.addAll(compiled.premises());
    compiled.labels().forEach((atom,label)->symbols.put("eventAtom:"+atom,label));
    for(int i=0;i<readings.size();i++){var r=readings.get(i);if(!r.accepted())readings.set(i,new Reading(r.start(),r.end(),"EVENT",true,"Rôles validés ; effets et persistance selon les schémas événementiels déclarés, sans question"));}
   }catch(EventLogicCompiler.Validation ex){issues.add("Compilation événementielle refusée : "+ex.getMessage());}
  }
  if(premises.size()>128)issues.add("Budget combiné de 128 prémisses dépassé");
  return new Registered(source,readings,predicates,premises,symbols,issues);
 }
 /** Adapter to the existing finite-domain reader. It does not invent a universal open-world axiom. */
 private static final class Quantified {
  final ControlledFrench language=new ControlledFrench();final Map<String,SemanticIntermediateRepresentation.EntityRef> entities=new LinkedHashMap<>();
  final Map<String,Atom> known=new LinkedHashMap<>();final List<Predicate> predicates;final Map<String,String> symbols;
  Quantified(FrenchStructuredTranslator.Document d,List<Predicate> predicates,Map<String,String> symbols){
   this.predicates=predicates;this.symbols=symbols;
   var atoms=new LinkedHashSet<Atom>();d.propositions().forEach(p->atoms(p.expression(),atoms));
   for(var atom:atoms)if(atom.arguments().size()==1&&atom.arguments().getFirst() instanceof Constant constant){
    var entity=constant.entity();String label=symbols.get("entity:"+entity.name());
    if(label==null)continue;
    var match=Pattern.compile("^(?:le |la |l')?(document|dossier|porte|objet) ([a-z][a-z0-9]*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(label.toLowerCase(Locale.ROOT));
    if(!match.matches())continue;
    String kind=match.group(1),id=match.group(2),suffix=kind+"_"+id;
    if(entities.putIfAbsent(suffix,entity)==null)language.declareEntity(kind,id);
    String property=symbols.get("predicate:"+atom.predicate());if(property!=null&&property.startsWith("property:"))known.put(property.substring(9)+"_"+suffix,atom);
   }
  }
  Optional<List<Expression>> parse(String sentence){
   if(!sentence.strip().toLowerCase(Locale.ROOT).matches("^(?:tous|toutes|aucun|aucune|certains|certaines)\\b.*"))return Optional.empty();
   var category=Pattern.compile("\\b(documents?|dossiers?|portes?|objets?)\\b").matcher(sentence.toLowerCase(Locale.ROOT));
   if(!category.find())return Optional.empty();String noun=category.group(1);String kind=noun.endsWith("s")?noun.substring(0,noun.length()-1):noun;
   if(language.entities().stream().noneMatch(e->e.kind().equals(kind)))return Optional.empty();
   try{
    var finite=new FiniteQuantifierFrench(language).parse(sentence);
    var interpretation=finite.isPresent()?finite.orElseThrow().interpretation():language.interpret(sentence);
    if(interpretation.act()==ControlledFrench.Act.ASK)return Optional.empty();
    return Optional.of(interpretation.formulas().stream().map(this::convert).toList());
   }catch(IllegalArgumentException ex){return Optional.empty();}
  }
  Expression convert(Formula formula){
   if(formula instanceof Formula.Atom a){
    if(known.containsKey(a.name()))return known.get(a.name());
    for(var entry:entities.entrySet())if(a.name().endsWith("_"+entry.getKey())){
     String concept=a.name().substring(0,a.name().length()-entry.getKey().length()-1);String key="property:"+concept;
     String predicate=predicates.stream().map(Predicate::name).filter(id->key.equals(symbols.get("predicate:"+id))).findFirst().orElse(null);
     if(predicate==null){predicate="quant_p"+predicates.size();predicates.add(new Predicate(predicate,List.of("entity")));symbols.put("predicate:"+predicate,key);}
     var result=new Atom(predicate,List.of(new Constant(entry.getValue())));known.put(a.name(),result);return result;
    }
    throw new IllegalArgumentException("Entité quantifiée sans déclaration dans le texte");
   }
   if(formula instanceof Formula.And a)return new And(convert(a.left()),convert(a.right()));
   if(formula instanceof Formula.Implies i){if(i.conclusion() instanceof Formula.Bottom)return new Not(convert(i.premise()));return new Implies(convert(i.premise()),convert(i.conclusion()));}
   throw new IllegalArgumentException("Expression quantifiée hors adaptateur");
  }
  static void atoms(Expression e,Set<Atom> into){if(e instanceof Atom a)into.add(a);else if(e instanceof Not n)atoms(n.body(),into);else if(e instanceof And a){atoms(a.left(),into);atoms(a.right(),into);}else if(e instanceof Implies i){atoms(i.premise(),into);atoms(i.conclusion(),into);}}
 }
}
