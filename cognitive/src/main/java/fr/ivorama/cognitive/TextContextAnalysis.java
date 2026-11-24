package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Explication des structures reconnues et inférences vérifiées ; interprétation littéraire séparée. */
public final class TextContextAnalysis {
 public record Observation(String kind,String explanation,int start,int end,String excerpt){}
 public record Inference(String explanation,List<String> premiseIds,boolean proofChecked,String reasoning){public Inference(String explanation,List<String> premiseIds,boolean proofChecked){this(explanation,premiseIds,proofChecked,"");}public Inference{premiseIds=List.copyOf(premiseIds);}}
 public record Result(FrenchStructuredTranslator.Document document,List<Observation> observations,List<Inference> inferences,String status,Optional<TextReadingRegistry.Registered> registry){
  public Result(FrenchStructuredTranslator.Document document,List<Observation> observations,List<Inference> inferences,String status){this(document,observations,inferences,status,Optional.empty());}
  public boolean formallyComplete(){return registry.map(TextReadingRegistry.Registered::complete).orElse(document.unresolved().isEmpty());}
  public Result{observations=List.copyOf(observations);inferences=List.copyOf(inferences);}
  public String describe(){
   StringJoiner out=new StringJoiner("\n");out.add("Analyse : "+status);
   int total=document.segments().stream().mapToInt(s->s.source().length()).sum();
   int formal=registry.map(r->r.readings().stream().filter(TextReadingRegistry.Reading::accepted).mapToInt(s->s.end()-s.start()).sum()).orElseGet(()->document.segments().stream().filter(FrenchStructuredTranslator.Segment::interpreted).mapToInt(s->s.source().length()).sum());
   out.add("Couverture du fragment formel : "+formal+" / "+total+" caractères des segments (ponctuation séparatrice exclue), soit "+(total==0?0:100*formal/total)+" %. Ce taux ne mesure pas la compréhension générale.");
   for(var o:observations)out.add("- "+o.kind()+" ["+o.start()+":"+o.end()+"] « "+o.excerpt()+" » : "+o.explanation());
   for(var i:inferences){
    out.add("- Déduction vérifiée sous les prémisses indiquées"+(i.reasoning().isEmpty()?" : "+i.explanation():"")+" ; sources "+i.premiseIds());
    if(!i.reasoning().isEmpty())out.add(i.reasoning());
    for(String id:i.premiseIds())registry.map(TextReadingRegistry.Registered::premises).orElse(document.propositions()).stream().filter(p->p.id().equals(id)).findFirst().ifPresent(p->out.add("Source "+id+" : « "+p.evidence().excerpt()+" »"));
   }
   if(!formallyComplete())out.add("Passages hors analyse formelle : "+document.unresolved()+". Aucune déduction globale sur une lecture partielle.");
   return out.toString();
  }
 }
 private final FrenchStructuredTranslator translator=new FrenchStructuredTranslator();
 private final CreativeTextEngine.Vocabulary vocabulary;
 public TextContextAnalysis(CreativeTextEngine.Vocabulary vocabulary){this.vocabulary=vocabulary;}
 public Result analyze(String source){
  var d=translator.readDocument(translator.analyzeText(source).json());
  return assess(d,List.of());
 }
 public Result reassess(Result baseline,List<Premise> hypotheses){
  if(hypotheses.size()>12||hypotheses.stream().anyMatch(p->p.evidence().origin()!=Origin.WEB_EXCERPT_UNVERIFIED))throw new IllegalArgumentException("Hypothèses documentaires explicites requises");
  return assess(baseline.document(),List.copyOf(hypotheses));
 }
 private Result assess(FrenchStructuredTranslator.Document d,List<Premise> hypotheses){
  var readers=new TextReadingRegistry();var registered=readers.read(readers.analyze(d.sourceText()).json());
  Map<String,String> symbols=registered.symbols();
  List<Observation> observations=new ArrayList<>();
  for(var segment:d.segments()){
   if(segment.interpreted()){
    var p=registered.premises().stream().filter(x->x.evidence().source().equals("texte:"+segment.start()+":"+segment.end())).findFirst().orElseThrow();String kind=p.expression() instanceof Implies?"CONDITION":p.expression() instanceof Not?"NÉGATION":"ASSERTION";
    observations.add(new Observation(kind,explain(p.expression(),symbols)+(p.expression() instanceof Implies?" La condition n'est pas affirmée comme réalisée.":" Déclaration du texte, pas fait authentifié."),segment.start(),segment.end(),segment.source()));
   }else if(registered.readings().stream().anyMatch(r->r.start()==segment.start()&&r.end()==segment.end()&&r.accepted())){
    var reading=registered.readings().stream().filter(r->r.start()==segment.start()&&r.end()==segment.end()).findFirst().orElseThrow();
    observations.add(new Observation(reading.reader(),reading.detail()+". Déclaration conditionnelle, pas fait authentifié.",segment.start(),segment.end(),segment.source()));
   }else{
    try{
     var clauses=FrenchTextRealizer.read(segment.source()+".",vocabulary);
     for(var c:clauses){var verb=vocabulary.verb(c.verb());
      observations.add(new Observation(verb.humanAction()?"INTERPRÉTATION POSSIBLE":"DESCRIPTION LEXICALE",verb.humanAction()?"Le verbe « "+verb.surface()+" » est employé avec « "+vocabulary.noun(c.subject()).word()+" » dans un cadre personnifiant déclaré : interprétation possible. Cela ne prouve pas l'intention de l'auteur.":"Sujet « "+vocabulary.noun(c.subject()).word()+" », action « "+verb.surface()+" » reconnue par la grammaire.",segment.start(),segment.end(),segment.source()));}
    }catch(IllegalArgumentException ignored){observations.add(new Observation("NON RÉSOLU","Le passage est conservé ; son sens n'est pas déduit par le fragment disponible.",segment.start(),segment.end(),segment.source()));}
   }
  }
  List<Inference> inferred=new ArrayList<>();String status=registered.issues().stream().anyMatch(i->i.startsWith("Budget"))?"RESOURCE_LIMIT":registered.complete()?"STRUCTURE_RECOGNIZED":"PARTIAL_ANALYSIS";
  if(registered.complete()&&!registered.premises().isEmpty()){
   Map<Atom,Formula> atoms=new LinkedHashMap<>();Map<String,Formula> premises=new LinkedHashMap<>();
   for(var p:registered.premises())premises.put(p.id(),project(p.expression(),atoms));
   for(var p:hypotheses)if(premises.putIfAbsent(p.id(),project(p.expression(),atoms))!=null)throw new IllegalArgumentException("Identifiant de prémisse dupliqué");
   var consistency=ModelExplorer.analyze(premises.values(),Formula.Bottom.INSTANCE,ModelExplorer.Budget.small());
   if(consistency.status()==ModelExplorer.Status.INCONSISTENT)status="INCONSISTENT_TEXT";
   else if(consistency.status()==ModelExplorer.Status.RESOURCE_LIMIT)status="RESOURCE_LIMIT";
   else{
    List<Expression> targets=new ArrayList<>();
    // Les deux polarités de tous les atomes réellement présents, pas des réponses attendues.
    for(var atom:List.copyOf(atoms.keySet())){targets.add(atom);targets.add(new Not(atom));}
    registered.premises().forEach(p->consequents(p.expression(),targets));Set<Formula> visited=new HashSet<>();
    int attempted=0;boolean truncated=false;
    for(var target:targets){Formula goal=project(target,atoms);if(!visited.add(goal)||premises.containsValue(goal))continue;
     if(attempted>=32){truncated=true;break;}attempted++;
     var term=ProofSearch.find(premises.values(),goal,512);
     var proof=term.map(p->ProofChecker.verify(p,premises.values())).filter(p->p.valid()&&p.conclusion().equals(goal));
     if(proof.isPresent()){
      var dependencies=proof.get().dependencies();var ids=premises.entrySet().stream().filter(e->dependencies.contains(e.getValue())).map(Map.Entry::getKey).toList();
      String reasoning=FrenchProofExplanation.renderChecked(goal,term.orElseThrow(),premises.values(),f->verbalize(f,atoms,symbols));
      inferred.add(new Inference(target instanceof Not n?"négation de ("+explain(n.body(),symbols)+")":explain(target,symbols),ids,true,reasoning));
     }
    }
    observations.add(new Observation("SEARCH_BUDGET","Cibles examinées : "+attempted+" / 32 ; recherche incomplète : 512 formules par sous-recherche, profondeur : implications 4, conjonctions 8. L'absence de preuve n'est pas une preuve d'indétermination."+(truncated?" Cibles restantes non examinées.":""),0,0,""));
    if(truncated)status="RESOURCE_LIMIT";
   }
  }
  return new Result(d,observations,inferred,status,Optional.of(registered));
 }
 private static void consequents(Expression e,List<Expression> result){if(e instanceof Implies i){result.add(i.conclusion());consequents(i.conclusion(),result);}else if(e instanceof And a){consequents(a.left(),result);consequents(a.right(),result);}}
 private static Formula project(Expression e,Map<Atom,Formula> atoms){
  if(e instanceof Atom a)return atoms.computeIfAbsent(a,k->Formula.atom("T"+atoms.size()));
  if(e instanceof Not n)return Formula.not(project(n.body(),atoms));
  if(e instanceof And a)return Formula.and(project(a.left(),atoms),project(a.right(),atoms));
  if(e instanceof Implies i)return Formula.implies(project(i.premise(),atoms),project(i.conclusion(),atoms));
  throw new IllegalArgumentException("Projection documentaire hors fragment propositionnel");
 }
 private static String verbalize(Formula f,Map<Atom,Formula> atoms,Map<String,String> symbols){
  if(f instanceof Formula.Atom){var atom=atoms.entrySet().stream().filter(e->e.getValue().equals(f)).findFirst().orElseThrow().getKey();return explain(atom,symbols);}
  if(f instanceof Formula.And a)return "("+verbalize(a.left(),atoms,symbols)+") et ("+verbalize(a.right(),atoms,symbols)+")";
  if(f instanceof Formula.Implies i){
   if(i.conclusion() instanceof Formula.Bottom&&i.premise() instanceof Formula.Atom){var atom=atoms.entrySet().stream().filter(e->e.getValue().equals(i.premise())).findFirst().orElseThrow().getKey();return explain(new Not(atom),symbols);}
   if(i.conclusion() instanceof Formula.Bottom)return "il est faux que ("+verbalize(i.premise(),atoms,symbols)+")";
   return "si ("+verbalize(i.premise(),atoms,symbols)+"), alors ("+verbalize(i.conclusion(),atoms,symbols)+")";
  }
  return "contradiction entre les hypothèses";
 }
 private static String explain(Expression e,Map<String,String> symbols){
  if(e instanceof Atom a)return atomText(a,false,symbols);
  if(e instanceof Not n)return n.body() instanceof Atom a?atomText(a,true,symbols):"il est faux que ("+explain(n.body(),symbols)+")";
  if(e instanceof And a)return "("+explain(a.left(),symbols)+") et ("+explain(a.right(),symbols)+")";
  var i=(Implies)e;return "si ("+explain(i.premise(),symbols)+"), alors ("+explain(i.conclusion(),symbols)+")";
 }
 private static String atomText(Atom atom,boolean negative,Map<String,String> symbols){
  String event=symbols.get("eventAtom:"+atom);
  if(event!=null)return (negative?"non (":"")+event+(negative?")":"");
  var t=(Constant)atom.arguments().getFirst();String entity=symbols.get("entity:"+t.entity().name());
  String property=symbols.getOrDefault("predicate:"+atom.predicate(),atom.predicate());boolean type=property.startsWith("class:");
  int colon=property.indexOf(':');String label=colon<0?property:property.substring(colon+1);
  return entity+(type?(negative?" ne relève pas de la classe « ":" relève de la classe « "):(negative?" ne possède pas la propriété « ":" possède la propriété « "))+label+" »";
 }
}
