package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.EventNarrative.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Compilation d'un récit fini en contraintes temporelles. Les mêmes schémas s'appliquent à tous les rôles. */
public final class EventLogicCompiler {
 public static final class Validation extends IllegalArgumentException {public Validation(String message){super(message);}}
 public record DocumentConstraints(List<EntityRef> entities,List<Predicate> predicates,List<Premise> premises,Map<Atom,String> labels){
  public DocumentConstraints{entities=List.copyOf(entities);predicates=List.copyOf(predicates);premises=List.copyOf(premises);labels=Map.copyOf(labels);}
 }
 public DocumentConstraints compileDocument(Document document){return new Run(document).documentConstraints();}
 public record Compiled(Problem problem,Map<String,Expression> candidates,Optional<Expression> presupposition){public Compiled{candidates=Collections.unmodifiableMap(new LinkedHashMap<>(candidates));Objects.requireNonNull(presupposition);}}
 private record Fluent(String relation,List<String> args){private Fluent{args=List.copyOf(args);}}
 private final EventRuleRegistry registry;
 public EventLogicCompiler(EventRuleRegistry registry){this.registry=Objects.requireNonNull(registry);}
 public Compiled compile(Narrative n){return new Run(n).compile();}
 private final class Run {
  final Document n;final Query query;final Map<String,EntityRef> entities=new LinkedHashMap<>();final List<Predicate> predicates=new ArrayList<>();
  final List<Premise> premises=new ArrayList<>();final Map<String,String> relationNames=new LinkedHashMap<>();final List<Fluent> universe=new ArrayList<>();
  Run(Narrative n){this.n=new Document(n.source(),n.entities(),n.agents(),n.objects(),n.initial(),n.events());this.query=n.query();}
  Run(Document n){this.n=n;this.query=null;}
  List<String> domain(String kind){return switch(kind){case "agent"->n.agents();case "object"->n.objects();default->throw new Validation("Domaine narratif inconnu : "+kind);};}
  void entity(String id,String kind){if(!n.entities().containsKey(id)||entities.putIfAbsent(id,new EntityRef(kind,id))!=null)throw new Validation("Référent absent ou domaines superposés");}
  void base(){
   n.agents().forEach(e->entity(e,"agent"));n.objects().forEach(e->entity(e,"object"));
   if(entities.size()!=n.entities().size()||entities.isEmpty())throw new Validation("Référents non typés");
   int count=0;
   for(var r:registry.relations().values()){
    relationNames.put(r.id(),"r"+count++);
    for(int t=0;t<=n.events().size();t++)predicates.add(new Predicate(name(r.id(),t),r.domains()));
    tuples(r,0,new ArrayList<>());
   }
   // Exclusivité uniquement des relations déclarées fonctionnelles, à chaque instant.
   for(int t=0;t<=n.events().size();t++)for(int i=0;i<universe.size();i++)for(int j=i+1;j<universe.size();j++){
    Fluent a=universe.get(i),b=universe.get(j);if(rivals(a,b))add(new Implies(atom(a,t),new Not(atom(b,t))),ruleEvidence("functional:"+a.relation()));
   }
   for(var f:n.initial())add(literal(f.relation(),f.arguments(),f.positive(),0),new Evidence(Origin.DECLARATION,"texte-fr:"+f.span().start()+":"+f.span().end(),f.span().excerpt()));
   for(int t=1;t<=n.events().size();t++){
    Event e=n.events().get(t-1);var rule=registry.rules().get(e.rule());if(rule==null||!e.roles().keySet().equals(rule.roles().keySet()))throw new Validation("Action ou rôles non déclarés");
    rule.roles().forEach((role,kind)->{if(!domain(kind).contains(e.roles().get(role)))throw new Validation("Participant incompatible avec le rôle");});
    var evidence=new Evidence(Origin.DOMAIN_RULE,"ivoc.events.1:"+rule.id()+":texte-fr:"+e.span().start()+":"+e.span().end(),e.span().excerpt()+" [événement déclaré accompli ; schéma "+rule.id()+e.secondsBeforeNow().stream().mapToObj(seconds->" ; repère : "+seconds+" secondes avant l’énonciation").findFirst().orElse("")+"]");
    for(var pre:rule.preconditions())add(literal(pre.relation(),resolve(pre.roles(),e),pre.positive(),t-1),evidence);
    Map<Fluent,Boolean> updates=new LinkedHashMap<>();
    for(var effect:rule.effects()){
     Fluent f=fluent(effect.relation(),resolve(effect.roles(),e));put(updates,f,effect.positive());
     if(effect.positive())for(var other:universe)if(rivals(f,other))put(updates,other,false);
    }
    for(var f:universe){
     if(updates.containsKey(f))add(updates.get(f)?atom(f,t):new Not(atom(f,t)),evidence);
     else {add(new Implies(atom(f,t-1),atom(f,t)),ruleEvidence("frame:"+rule.id()));add(new Implies(atom(f,t),atom(f,t-1)),ruleEvidence("frame:"+rule.id()));}
    }
   }
  }
  DocumentConstraints documentConstraints(){
   base();var labels=new LinkedHashMap<Atom,String>();
   for(var f:universe)for(int t=0;t<=n.events().size();t++)labels.put(atom(f,t),f.relation()+"("+String.join(", ",f.args().stream().map(n.entities()::get).toList())+") à l'étape "+t);
   return new DocumentConstraints(new ArrayList<>(entities.values()),predicates,premises,labels);
  }
  Compiled compile(){
   base();Query q=Objects.requireNonNull(query);var relation=registry.relations().get(q.relation());
   if(relation==null||q.arguments().size()!=relation.domains().size()||q.answerSlot()<-1||q.answerSlot()>=q.arguments().size())throw new Validation("Question relationnelle invalide");
   Expression goal;Map<String,Expression> candidates=new LinkedHashMap<>();
   if(q.answerSlot()>=0){
    if(!q.arguments().get(q.answerSlot()).equals("?"))throw new Validation("Case interrogative manquante");
    List<Term> args=new ArrayList<>();
    for(int i=0;i<q.arguments().size();i++)args.add(i==q.answerSlot()?new Variable("answer"):constant(q.arguments().get(i),relation.domains().get(i)));
    goal=new Exists("answer",relation.domains().get(q.answerSlot()),new Atom(name(q.relation(),q.time()),args));
    for(var id:domain(relation.domains().get(q.answerSlot()))){var fixed=new ArrayList<>(q.arguments());fixed.set(q.answerSlot(),id);candidates.put(id,atom(fluent(q.relation(),fixed),q.time()));}
   }else goal=atom(fluent(q.relation(),q.arguments()),q.time());
   Optional<Expression> presupposition=q.aspect()==Aspect.STILL?Optional.of(atom(fluent(q.relation(),q.arguments()),q.time()-1)):Optional.empty();
   return new Compiled(new Problem(n.source(),new ArrayList<>(entities.values()),predicates,premises,goal,List.of(),List.of()),candidates,presupposition);
  }
  void put(Map<Fluent,Boolean> updates,Fluent f,boolean value){Boolean old=updates.putIfAbsent(f,value);if(old!=null&&old!=value)throw new Validation("Effets contradictoires du même événement (rôles identiques ?)");}
  List<String> resolve(List<String> roles,Event e){return roles.stream().map(e.roles()::get).toList();}
  Evidence ruleEvidence(String name){return new Evidence(Origin.DOMAIN_RULE,"ivoc.events.1:"+name,"Convention explicite : récit fini, événements accomplis dans l'ordre indiqué ; persistance des seuls fluents modélisés hors effets déclarés ; domaine nommé.");}
  void add(Expression e,Evidence source){if(premises.size()>=128)throw new Validation("Budget de 128 contraintes narratives dépassé");premises.add(new Premise("ev_p"+premises.size(),e,source));}
  String name(String relation,int t){String base=relationNames.get(relation);if(base==null)throw new Validation("Relation inconnue");return base+"_t"+t;}
  void tuples(EventRuleRegistry.Relation r,int i,List<String> prefix){if(i==r.domains().size()){universe.add(new Fluent(r.id(),prefix));return;}for(String id:domain(r.domains().get(i))){var p=new ArrayList<>(prefix);p.add(id);tuples(r,i+1,p);}}
  boolean rivals(Fluent a,Fluent b){
   if(!a.relation().equals(b.relation())||a.equals(b))return false;int slot=registry.relations().get(a.relation()).functionalSlot();if(slot<0)return false;
   for(int i=0;i<a.args().size();i++)if(i!=slot&&!a.args().get(i).equals(b.args().get(i)))return false;return true;
  }
  Fluent fluent(String relation,List<String> args){var r=registry.relations().get(relation);if(r==null||args.size()!=r.domains().size())throw new Validation("Signature de fluent invalide");for(int i=0;i<args.size();i++)constant(args.get(i),r.domains().get(i));return new Fluent(relation,args);}
  Constant constant(String id,String kind){EntityRef e=entities.get(id);if(e==null||!e.kind().equals(kind))throw new Validation("Argument absent ou incompatible");return new Constant(e);}
  Atom atom(Fluent f,int t){var r=registry.relations().get(f.relation());List<Term> args=new ArrayList<>();for(int i=0;i<f.args().size();i++)args.add(constant(f.args().get(i),r.domains().get(i)));return new Atom(name(f.relation(),t),args);}
  Expression literal(String relation,List<String> args,boolean positive,int t){var a=atom(fluent(relation,args),t);return positive?a:new Not(a);}
 }
}
