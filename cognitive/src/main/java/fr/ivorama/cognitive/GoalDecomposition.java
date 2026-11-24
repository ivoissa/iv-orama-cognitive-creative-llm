package fr.ivorama.cognitive;
import java.util.*;

/** Sous-buts inspectables, évalués sous le même contexte. Le verdict conjoint reste souverain. */
public final class GoalDecomposition {
 private GoalDecomposition() {}
 public record Node(String path,Formula goal,ModelExplorer.Status status) {}
 public record Report(List<Node> nodes,boolean truncated) {
  public Report {nodes=List.copyOf(nodes);}
 }
 private record Pending(String path,Formula goal) {}
 public static Report analyze(Collection<Formula> premises,Formula goal,
                              ModelExplorer.Analysis joint,ModelExplorer.Budget budget,int maxNodes) {
  Objects.requireNonNull(joint);Objects.requireNonNull(budget);
  if(maxNodes<1||maxNodes>64)throw new IllegalArgumentException("Budget de 1 à 64 sous-buts requis");
  List<Formula> context=List.copyOf(premises);
  var nodes=new ArrayList<Node>();
  nodes.add(new Node("root",goal,joint.status()));
  // Aucun diagnostic partiel après une contradiction ou une limite du solveur conjoint.
  if(joint.status()==ModelExplorer.Status.INCONSISTENT||joint.status()==ModelExplorer.Status.RESOURCE_LIMIT)
   return new Report(nodes,false);
  var pending=new ArrayDeque<Pending>();
  if(goal instanceof Formula.And a) {pending.add(new Pending("root.left",a.left()));pending.add(new Pending("root.right",a.right()));}
  while(!pending.isEmpty()&&nodes.size()<maxNodes) {
   Pending next=pending.removeFirst();
   var result=ModelExplorer.analyze(context,next.goal(),budget);
   nodes.add(new Node(next.path(),next.goal(),result.status()));
   if(next.goal() instanceof Formula.And a) {
    pending.add(new Pending(next.path()+".left",a.left()));
    pending.add(new Pending(next.path()+".right",a.right()));
   }
  }
  return new Report(nodes,!pending.isEmpty());
 }
}
