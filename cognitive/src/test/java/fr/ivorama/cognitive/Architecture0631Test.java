package fr.ivorama.cognitive;
import java.nio.file.*;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Régressions d'architecture et oracle exhaustif indépendant sur deux variables. */
public final class Architecture0631Test {
 private static int checks;
 private static void ok(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 private static ModelExplorer.Status oracle(List<Formula> context,Formula goal){
  int yes=0,no=0;
  for(int bits=0;bits<4;bits++){
   var world=Map.of("p",(bits&1)!=0,"q",(bits&2)!=0);
   if(context.stream().allMatch(f->f.evaluate(world))) {if(goal.evaluate(world))yes++;else no++;}
  }
  return yes+no==0?ModelExplorer.Status.INCONSISTENT:no==0?ModelExplorer.Status.PROVEN_TRUE:
      yes==0?ModelExplorer.Status.PROVEN_FALSE:ModelExplorer.Status.UNDETERMINED;
 }
 private static void goals(){
  Formula p=Formula.atom("p"),q=Formula.atom("q"),goal=Formula.and(p,q);
  List<Formula> candidates=List.of(p,q,Formula.not(p),Formula.not(q),Formula.not(goal),Formula.implies(p,q));
  for(int mask=0;mask<64;mask++){
   List<Formula> context=new ArrayList<>();
   for(int i=0;i<6;i++)if((mask&(1<<i))!=0)context.add(candidates.get(i));
   var joint=ModelExplorer.analyze(context,goal,ModelExplorer.Budget.small());
   var result=GoalDecomposition.analyze(context,goal,joint,ModelExplorer.Budget.small(),17);
   ok(result.nodes().get(0).status()==oracle(context,goal),"Oracle conjoint "+mask);
   for(var node:result.nodes())ok(node.status()==oracle(context,node.goal()),"Oracle sous-but "+mask);
   if(joint.status()==ModelExplorer.Status.INCONSISTENT)ok(result.nodes().size()==1,"Pas de décomposition après conflit");
  }
  var context=List.of(Formula.not(goal));var joint=ModelExplorer.analyze(context,goal,ModelExplorer.Budget.small());
  var result=GoalDecomposition.analyze(context,goal,joint,ModelExplorer.Budget.small(),17);
  ok(joint.status()==ModelExplorer.Status.PROVEN_FALSE && result.nodes().subList(1,3).stream().allMatch(n->n.status()==ModelExplorer.Status.UNDETERMINED),"Dépendance conjointe : ne pas agréger UNKNOWN/UNKNOWN");
  ok(GoalDecomposition.analyze(context,goal,joint,ModelExplorer.Budget.small(),1).truncated(),"Budget du diagnostic explicite");
  var entity=new SemanticIntermediateRepresentation.EntityRef("item","a");
  Expression a=new Atom("p",List.of(new Constant(entity))),b=new Atom("q",List.of(new Constant(entity)));
  var problem=new Problem("But conjoint",List.of(entity),List.of(new Predicate("p",List.of("item")),new Predicate("q",List.of("item"))),
   List.of(new Premise("given",new Not(new And(a,b)),new Evidence(Origin.DECLARATION,"test","Déclaration"))),new And(a,b),List.of(),List.of());
  var cycle=new IntegratedQuestionCycle();var service=new StructuredReasoningService(cycle);
  var answer=service.askJson(StructuredJsonWriter.write(problem));
  ok(answer.status()==StructuredReasoningService.Status.FALSE_CONDITIONALLY && answer.goalDetails().orElseThrow().nodes().size()==3,"Intégration après JSON");
  ok(cycle.questionActCount()==1&&cycle.structuredRepresentation(answer.questionId()).orElseThrow().problem().goal().equals(problem.goal()),"Sous-buts sans nouvel acte ni remplacement du but");
  ok(service.revisit(answer.questionId()).status()==answer.status(),"Révision du but original");
 }
 private static CompositionalReasoningEngine.Literal lit(String s){return new CompositionalReasoningEngine.Literal(s,true);}
 private static void cache(){
  var engine=new CompositionalReasoningEngine();
  engine.fact("f1",lit("a"),"déclaration");engine.rule("r1",List.of(lit("a")),lit("b"),"règle");
  var first=engine.ask(lit("b"));var before=engine.diagnostics();var second=engine.revisit(lit("b"));var after=engine.diagnostics();
  ok(first.state()==CompositionalReasoningEngine.State.YES&&first.indispensableSources().equals(second.indispensableSources()),"Verdict et sources conservés");
  ok(before.solverCalls()==2&&after.solverCalls()==before.solverCalls()&&after.cacheHits()-before.cacheHits()==2,"Deux appels évités lors de la révision");
  engine.fact("f2",lit("b"),"preuve alternative");
  ok(engine.diagnostics().cachedChecks()==0,"Invalidation à l'ajout");
  ok(engine.revisit(lit("b")).indispensableSources().isEmpty(),"Aucune source indispensable avec deux preuves indépendantes");
  engine.withdraw("f2");ok(engine.diagnostics().cachedChecks()==0,"Invalidation au retrait");
  ok(new HashSet<>(engine.revisit(lit("b")).indispensableSources()).equals(Set.of("f1","r1")),"Indispensabilité restaurée après retrait");
  System.out.println("Cache : premier passage="+before.solverCalls()+" appels ; révision inchangée="+(after.solverCalls()-before.solverCalls())+" appel, "+(after.cacheHits()-before.cacheHits())+" réutilisations.");
 }
 private static void benchmark() throws Exception {
  Path file=Files.createTempFile("ivoc-architecture-", ".tsv");
  try{
   var lines=new ArrayList<String>();
   for(int i=0;i<100;i++)lines.add("case"+i+"\tdocument d"+i+"\tLe document d"+i+" est valide.\tLe document d"+i+" est-il valide ?\tYES\t\tYES");
   Files.write(file,lines);var score=Benchmark100.evaluate(file);
   ok(score.correctInitial()==100&&score.correctFinal()==100,"100 scénarios mécaniques de test du banc, pas un benchmark de généralisation");
   lines.set(0,lines.get(0).replace("\tYES\t\tYES","\tYES\taction_invalide\tREJECT"));Files.write(file,lines);
   boolean propagated=false;try{Benchmark100.evaluate(file);}catch(IllegalArgumentException ex){propagated=ex.getMessage().contains("Action de benchmark inconnue");}
   ok(propagated,"Action erronée jamais transformée en REJECT correct");
   lines.set(0,lines.get(0).replace("action_invalide","observe oui"));Files.write(file,lines);
   boolean statePropagated=false;try{Benchmark100.evaluate(file);}catch(IllegalStateException ex){statePropagated=ex.getMessage().contains("Aucune observation en attente");}
   ok(statePropagated,"Invariant d'observation sans attente jamais transformé en REJECT correct");
   lines.set(0,"case0\tdocument d0\t\tUne question hors fragment ?\tREJECT\t\tREJECT");Files.write(file,lines);
   ok(Benchmark100.evaluate(file).correctInitial()==100,"Rejet grammatical explicite toujours reconnu");
  }finally{Files.deleteIfExists(file);}
 }
 private static void french(){
  var service=new FrenchStructuredReasoningService(new IntegratedQuestionCycle());
  var answer=service.respond("Le document d1 est valide et le document d1 n'est pas archive. Est-ce que le document d1 est valide et est-ce que le document d1 est archive ?");
  ok(answer.answer().status()==StructuredReasoningService.Status.FALSE_CONDITIONALLY,"Coordination de questions complètes");
  for(String noun:List.of("capteur","rocher","signal")) {
   var translated=new FrenchStructuredTranslator().translate("Le "+noun+" z1 est stable. Est-ce que le "+noun+" z1 est stable et est-ce que le "+noun+" z1 est stable ?");
   ok(translated.complete(),"Même construction avec nom de domaine inédit : "+noun);
  }
 }
 public static void main(String[] args)throws Exception{goals();cache();benchmark();french();System.out.println("Architecture0631Test : "+checks+" vérifications OK");}
}
