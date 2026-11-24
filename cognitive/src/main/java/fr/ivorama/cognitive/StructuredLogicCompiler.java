package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Instanciation exacte sur les seuls individus déclarés ; aucune approximation partielle publiée. */
public final class StructuredLogicCompiler {
 public static final class Limit extends RuntimeException {public Limit(String text){super(text);}}
 public record Compiled(List<Formula> premises,Formula goal,List<RdoPlanner.Candidate> observables,
                        Map<String,GroundAtom> symbols,Map<String,Formula> premiseIds){
  public Compiled{premises=List.copyOf(premises);observables=List.copyOf(observables);symbols=Map.copyOf(symbols);premiseIds=Collections.unmodifiableMap(new LinkedHashMap<>(premiseIds));}
 }
 private final Map<String,List<EntityRef>> domains=new HashMap<>();
 private final Map<String,Predicate> predicates=new HashMap<>();
 private final Map<GroundAtom,String> symbols=new LinkedHashMap<>();
 private int visited;
 private StructuredLogicCompiler(){}
 public static Compiled compile(Problem p){return new StructuredLogicCompiler().run(p);}
 private Compiled run(Problem p){
  if(!p.unresolved().isEmpty())throw new IllegalArgumentException("Interprétation incomplète");
  if(p.entities().size()>64||p.predicates().size()>64||p.premises().size()>128||p.observables().size()>16)throw new Limit("Taille du problème au-delà des limites");
  Set<EntityRef> unique=new HashSet<>();
  for(var e:p.entities()){
   identifier(e.kind());identifier(e.name());
   if(!unique.add(e))throw new IllegalArgumentException("Entité déclarée deux fois");
   domains.computeIfAbsent(e.kind(),k->new ArrayList<>()).add(e);
  }
  for(var predicate:p.predicates())if(predicates.putIfAbsent(predicate.name(),predicate)!=null)throw new IllegalArgumentException("Prédicat déclaré deux fois");
  Map<String,Formula> premises=new LinkedHashMap<>();
  for(var premise:p.premises()){
   if(premises.containsKey(premise.id()))throw new IllegalArgumentException("Identifiant de prémisse dupliqué");
   premises.put(premise.id(),expression(premise.expression(),Map.of(),0));
  }
  Formula goal=expression(p.goal(),Map.of(),0);
  List<RdoPlanner.Candidate> observations=new ArrayList<>();
  for(var o:p.observables())observations.add(new RdoPlanner.Candidate(expression(o.expression(),Map.of(),0),o.cost()));
  Map<String,GroundAtom> reverse=new LinkedHashMap<>();symbols.forEach((atom,name)->reverse.put(name,atom));
  return new Compiled(new ArrayList<>(premises.values()),goal,observations,reverse,premises);
 }
 private Formula expression(Expression e,Map<String,EntityRef> bound,int depth){
  if(++visited>10000||depth>64)throw new Limit("Budget d'instanciation ou profondeur dépassé");
  if(e instanceof Atom a){
   Predicate def=predicates.get(a.predicate());
   if(def==null||def.kinds().size()!=a.arguments().size())throw new IllegalArgumentException("Prédicat inconnu ou arité incorrecte");
   List<EntityRef> args=new ArrayList<>();
   for(int i=0;i<a.arguments().size();i++){
    Term term=a.arguments().get(i);EntityRef value=term instanceof Constant c?c.entity():bound.get(((Variable)term).name());
    if(value==null)throw new IllegalArgumentException("Variable libre");
    if(!value.kind().equals(def.kinds().get(i))||!domains.getOrDefault(value.kind(),List.of()).contains(value))throw new IllegalArgumentException("Argument non déclaré ou type incompatible");
    args.add(value);
   }
   GroundAtom ground=new GroundAtom(a.predicate(),args);
   String name=symbols.get(ground);
   if(name==null){if(symbols.size()>=256)throw new Limit("Plus de 256 atomes instanciés");name="G"+symbols.size();symbols.put(ground,name);}
   return Formula.atom(name);
  }
  if(e instanceof Not n)return Formula.not(expression(n.body(),bound,depth+1));
  if(e instanceof And a)return Formula.and(expression(a.left(),bound,depth+1),expression(a.right(),bound,depth+1));
  if(e instanceof Implies i)return Formula.implies(expression(i.premise(),bound,depth+1),expression(i.conclusion(),bound,depth+1));
  String variable,kind;Expression body;boolean existential=e instanceof Exists;
  if(e instanceof ForAll q){variable=q.variable();kind=q.kind();body=q.body();}
  else {var q=(Exists)e;variable=q.variable();kind=q.kind();body=q.body();}
  if(bound.containsKey(variable))throw new IllegalArgumentException("Masquage de variable refusé : renommer le lieur");
  List<EntityRef> members=domains.getOrDefault(kind,List.of());
  if(members.isEmpty())throw new IllegalArgumentException("Domaine quantifié vide ou non déclaré");
  List<Formula> instances=new ArrayList<>();
  for(var value:members){Map<String,EntityRef> next=new HashMap<>(bound);next.put(variable,value);Formula f=expression(body,next,depth+1);instances.add(existential?Formula.not(f):f);}
  Formula conjunction=join(instances,0,instances.size());return existential?Formula.not(conjunction):conjunction;
 }
 private Formula join(List<Formula> xs,int from,int to){if(to-from==1)return xs.get(from);int mid=(from+to)/2;return Formula.and(join(xs,from,mid),join(xs,mid,to));}
}
