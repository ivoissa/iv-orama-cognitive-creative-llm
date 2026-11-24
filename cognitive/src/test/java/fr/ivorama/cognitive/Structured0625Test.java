package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

public final class Structured0625Test {
 private static int checks;
 private static final EntityRef A=new EntityRef("item","a"),B=new EntityRef("item","b");
 private static final Evidence USER=new Evidence(Origin.DECLARATION,"fixture","Prémisse structurée de test");
 private static void ok(boolean b,String msg){checks++;if(!b)throw new AssertionError(msg);}
 private static Atom atom(String p,EntityRef e){return new Atom(p,List.of(new Constant(e)));}
 private static Atom var(String p,String v){return new Atom(p,List.of(new Variable(v)));}
 private static Premise premise(String id,Expression e){return new Premise(id,e,USER);}
 private static Problem problem(List<Premise> ps,Expression q){return new Problem("Problème symbolique de développement",List.of(A,B),List.of(new Predicate("p",List.of("item")),new Predicate("q",List.of("item"))),ps,q,List.of(),List.of());}
 private static StructuredReasoningService.Answer ask(Problem p){return new StructuredReasoningService(new IntegratedQuestionCycle()).ask(new SemanticIntermediateRepresentation.LogicProblemFrame(p));}
 private static void contracts(){
  var rule=new ForAll("x","item",new Implies(var("p","x"),var("q","x")));
  var ps=List.of(premise("rule",rule),premise("given",atom("p",A)));
  var cycle=new IntegratedQuestionCycle();var service=new StructuredReasoningService(cycle);
  var frame=new SemanticIntermediateRepresentation.LogicProblemFrame(problem(ps,atom("q",A)));
  var yes=service.ask(frame);
  ok(yes.status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"Instanciation puis modus ponens");
  ok(yes.checkedProof().isPresent() && yes.checkedProof().orElseThrow().valid(),"Preuve vérifiée par noyau existant");
  ok(cycle.questionActCount()==1 && cycle.structuredRepresentation(yes.questionId()).orElseThrow().equals(frame),"RSI intégrée, un seul acte");
  ok(cycle.premises().isEmpty(),"Prémisses locales pas enseignées globalement");
  ok(cycle.journal().get(0).operation().equals("QUESTION_POSED"),"FCI avant compilation et évaluation");
  ok(service.revisit(yes.questionId()).status()==yes.status() && cycle.questionActCount()==1,"Révision sans nouvel acte");
  ok(ask(problem(ps,atom("q",B))).status()==StructuredReasoningService.Status.UNDETERMINED,"Pas d'extension d'un fait à un autre individu");
  var no=ask(problem(List.of(premise("negative",new Not(atom("p",A)))),atom("p",A)));
  ok(no.status()==StructuredReasoningService.Status.FALSE_CONDITIONALLY && no.checkedProof().isPresent(),"Négation explicite");
  var unknown=ask(problem(List.of(),atom("p",A)));
  ok(unknown.status()==StructuredReasoningService.Status.UNDETERMINED && unknown.analysis().orElseThrow().trueWitness().isPresent() && unknown.analysis().orElseThrow().falseWitness().isPresent(),"Monde ouvert et témoins");
  var conflict=ask(problem(List.of(premise("positive",atom("p",A)),premise("negative",new Not(atom("p",A))),premise("irrelevant",atom("q",B))),atom("q",A)));
  ok(conflict.status()==StructuredReasoningService.Status.INCONSISTENT && conflict.checkedProof().isEmpty(),"Pas d'explosion");
  ok(Set.copyOf(conflict.conflictPremises()).equals(Set.of("positive","negative")),"Conflit explicable sans prémisse indépendante");
  var existential=ask(problem(List.of(premise("some",new Exists("x","item",var("p","x")))),atom("p",A)));
  ok(existential.status()==StructuredReasoningService.Status.UNDETERMINED,"Existentiel n'identifie pas son témoin");
  ok(ask(problem(List.of(premise("all",new ForAll("x","item",var("p","x")))),new Exists("x","item",var("p","x")))).status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"Tous et certains sur domaine non vide");
  List<Premise> withExtra=new ArrayList<>(ps);withExtra.add(premise("unrelated",atom("q",B)));
  ok(ask(problem(withExtra,atom("q",A))).status()==yes.status(),"Ajout sans rapport ne change pas conclusion cohérente");
  ok(ask(problem(List.of(premise("rule",rule)),atom("q",A))).status()==StructuredReasoningService.Status.UNDETERMINED,"Ablation fait disparaître preuve");
  var base=problem(List.of(premise("r",new Implies(atom("p",A),atom("q",A)))),atom("q",A));
  var observed=new Problem(base.sourceText(),base.entities(),base.predicates(),base.premises(),base.goal(),List.of(new StructuredLogic.Observable(atom("p",A),1)),List.of());
  ok(ask(observed).investigation().orElseThrow().choice().isPresent(),"RDO existant réutilisé avec observation déclarée");
  ok(ask(base).investigation().isEmpty(),"Aucun observable inventé");
  var hyp=new Premise("h",atom("p",A),new Evidence(Origin.HYPOTHESIS,"test","Hypothèse explicite"));
  ok(ask(problem(List.of(hyp),atom("p",A))).explanation().contains("aucune vérité empirique"),"Hypothèse ne certifie pas fait");
  for(String name:List.of("r","relation_z","predicate_42")){
   var q=new Atom(name,List.of(new Constant(A)));var renamed=new Problem("Renommage",List.of(A),List.of(new Predicate(name,List.of("item"))),List.of(premise("given",q)),q,List.of(),List.of());
   ok(ask(renamed).status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"Invariance nom du prédicat");
  }
 }
 private static void validation(){
  var base=problem(List.of(),atom("p",A));
  var incomplete=new Problem(base.sourceText(),base.entities(),base.predicates(),base.premises(),base.goal(),List.of(),List.of("Référence non résolue"));
  var partial=ask(incomplete);ok(partial.status()==StructuredReasoningService.Status.INCOMPLETE_INTERPRETATION && partial.compilation().isEmpty(),"Aucun calcul sur interprétation partielle");
  ok(ask(problem(List.of(),var("p","x"))).status()==StructuredReasoningService.Status.INVALID_INPUT,"Variable libre rejetée");
  ok(ask(problem(List.of(),atom("p",new EntityRef("other","a")))).status()==StructuredReasoningService.Status.INVALID_INPUT,"Mauvais type rejeté");
  ok(ask(problem(List.of(),atom("p",new EntityRef("item","c")))).status()==StructuredReasoningService.Status.INVALID_INPUT,"Individu non déclaré rejeté");
  ok(ask(problem(List.of(),new ForAll("x","missing",var("p","x")))).status()==StructuredReasoningService.Status.INVALID_INPUT,"Pas de quantification vide implicite");
  ok(ask(problem(List.of(),new ForAll("x","item",new Exists("x","item",var("p","x"))))).status()==StructuredReasoningService.Status.INVALID_INPUT,"Masquage variable rejeté sans approximation");
  var budget=new StructuredReasoningService(new IntegratedQuestionCycle(),new ModelExplorer.Budget(0,1));
  ok(budget.ask(new SemanticIntermediateRepresentation.LogicProblemFrame(base)).status()==StructuredReasoningService.Status.RESOURCE_LIMIT,"Limite distincte de l'indétermination");
  var cycle=new IntegratedQuestionCycle();var service=new StructuredReasoningService(cycle);
  ok(service.askJson("{}").status()==StructuredReasoningService.Status.INVALID_INPUT && cycle.questionActCount()==1,"FCI conservée pour JSON invalide");
  String json="""
   {"sourceText":"Entrée symbolique","entities":[{"kind":"item","name":"a"}],
    "predicates":[{"name":"p","kinds":["item"]}],
    "premises":[{"id":"given","origin":"DECLARATION","source":"test","excerpt":"p(a)","formula":{"atom":"p","args":["a"]}}],
    "goal":{"atom":"p","args":["a"]}}
   """;
  ok(service.askJson(json).status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"JSON traité par même adaptateur");
  ok(service.askJson(json.replace("\"goal\":","\"hiddenContext\":true,\"goal\":")).status()==StructuredReasoningService.Status.INVALID_INPUT,"Champs inconnus non ignorés");
  ok(service.askJson(json.replace("\"p(a)\"","\"\"")).status()==StructuredReasoningService.Status.INVALID_INPUT,"Provenance obligatoire");
  var relational=new Atom("r",List.of(new Constant(A),new Constant(B)));
  var p=new Problem("Relation binaire",List.of(A,B),List.of(new Predicate("r",List.of("item","item"))),List.of(premise("given",relational)),relational,List.of(),List.of());
  ok(ask(p).status()==StructuredReasoningService.Status.TRUE_CONDITIONALLY,"Relations à plusieurs arguments");
 }
 // Oracle : interprétation directe de l'AST quantifié, sans Formula ni compilateur.
 private static boolean eval(Expression e,Map<GroundAtom,Boolean> w,Map<String,EntityRef> env){
  if(e instanceof Atom a){List<EntityRef> args=new ArrayList<>();for(Term t:a.arguments())args.add(t instanceof Constant c?c.entity():env.get(((Variable)t).name()));return w.get(new GroundAtom(a.predicate(),args));}
  if(e instanceof Not n)return !eval(n.body(),w,env);
  if(e instanceof And a)return eval(a.left(),w,env)&&eval(a.right(),w,env);
  if(e instanceof Implies i)return !eval(i.premise(),w,env)||eval(i.conclusion(),w,env);
  boolean all=e instanceof ForAll;String variable=all?((ForAll)e).variable():((Exists)e).variable();Expression body=all?((ForAll)e).body():((Exists)e).body();
  for(EntityRef member:List.of(A,B)){var next=new HashMap<>(env);next.put(variable,member);boolean value=eval(body,w,next);if(all&&!value)return false;if(!all&&value)return true;}return all;
 }
 private static Expression random(Random r,int depth){
  if(depth==0)return atom(r.nextBoolean()?"p":"q",r.nextBoolean()?A:B);
  return switch(r.nextInt(6)){
   case 0 -> new Not(random(r,depth-1));
   case 1 -> new And(random(r,depth-1),random(r,depth-1));
   case 2 -> new Implies(random(r,depth-1),random(r,depth-1));
   case 3 -> new ForAll("x","item",new Implies(var("p","x"),var("q","x")));
   case 4 -> new Exists("x","item",new And(var("p","x"),new Not(var("q","x"))));
   default -> random(r,0);
  };
 }
 private static void differential(){
  Random random=new Random(6252026);
  for(int n=0;n<150;n++){
   List<Premise> ps=new ArrayList<>();for(int i=0;i<random.nextInt(4);i++)ps.add(premise("premise"+i,random(random,2)));
   Expression goal=random(random,2);boolean yes=false,no=false;
   for(int mask=0;mask<16;mask++){
    Map<GroundAtom,Boolean> world=new HashMap<>();int bit=0;
    for(String p:List.of("p","q"))for(EntityRef e:List.of(A,B))world.put(new GroundAtom(p,List.of(e)),(mask&(1<<bit++))!=0);
    if(ps.stream().allMatch(p->eval(p.expression(),world,Map.of()))){if(eval(goal,world,Map.of()))yes=true;else no=true;}
   }
   var expected=!yes&&!no?StructuredReasoningService.Status.INCONSISTENT:yes&&no?StructuredReasoningService.Status.UNDETERMINED:yes?StructuredReasoningService.Status.TRUE_CONDITIONALLY:StructuredReasoningService.Status.FALSE_CONDITIONALLY;
   var actual=ask(problem(ps,goal));ok(actual.status()==expected,"Oracle AST indépendant cas "+n);
   var compiled=actual.compilation().orElseThrow();var analysis=actual.analysis().orElseThrow();
   for(var witness:List.of(analysis.trueWitness(),analysis.falseWitness()))if(witness.isPresent()){
    Map<GroundAtom,Boolean> world=new HashMap<>();compiled.symbols().forEach((name,atom)->world.put(atom,witness.orElseThrow().values().get(name)));
    ok(ps.stream().allMatch(p->eval(p.expression(),world,Map.of())),"Témoin satisfait AST original");
    ok(eval(goal,world,Map.of())==witness.equals(analysis.trueWitness()),"Témoin respecte signe du but AST");
   }
  }
 }
 public static void main(String[] args){contracts();validation();differential();System.out.println("STRUCTURED-0625 OK - "+checks+" contrôles, dont 150 cas différentiels AST");}
}
