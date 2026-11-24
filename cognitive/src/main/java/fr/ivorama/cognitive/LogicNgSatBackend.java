package fr.ivorama.cognitive;

import java.util.*;
import org.logicng.formulas.FormulaFactory;
import org.logicng.solvers.MiniSat;
import org.logicng.handlers.TimeoutSATHandler;
import org.logicng.datastructures.Tristate;

/** LogicNG 2.6.2 adapter. Fresh per-call factory/solvers: no retained contexts.
 * SAT witnesses are re-evaluated with IV-ORAMA Formula. UNSAT remains a library
 * semantic verdict, never a formal proof. No possibleWorlds or UNSAT core claimed.
 * maxAtoms/maxAssignments belong to ModelExplorer; separate SAT limits are explicit.
 * Timeout is cooperative inside solving; conversion has node/depth limits.
 */
public final class LogicNgSatBackend implements ReasoningBackend {
    private final int maxNodes, maxDepth;
    private final long timeoutMillis;
    public LogicNgSatBackend() { this(100_000, 256, 1000); }
    public LogicNgSatBackend(int maxNodes, int maxDepth, long timeoutMillis) {
        if(maxNodes<1 || maxDepth<1 || maxDepth>256 || timeoutMillis<1) throw new IllegalArgumentException("Invalid SAT limits");
        this.maxNodes=maxNodes; this.maxDepth=maxDepth; this.timeoutMillis=timeoutMillis;
    }
    public String id() { return "logicng-2.6.2"; }
    private static final class Limit extends RuntimeException {}
    private final class Conversion {
        final FormulaFactory factory = new FormulaFactory();
        final SortedSet<String> names = new TreeSet<>();
        int visited;
        org.logicng.formulas.Formula convert(Formula f, int depth) {
            if(++visited>maxNodes || depth>maxDepth) throw new Limit();
            if(f instanceof Formula.Atom a) { names.add(a.name()); return factory.variable(a.name()); }
            if(f instanceof Formula.Bottom) return factory.falsum();
            if(f instanceof Formula.And a) return factory.and(convert(a.left(),depth+1),convert(a.right(),depth+1));
            Formula.Implies i=(Formula.Implies)f;
            return factory.implication(convert(i.premise(),depth+1),convert(i.conclusion(),depth+1));
        }
    }
    private record Check(Tristate state, Optional<ModelExplorer.World> witness) {}
    private Check solve(Conversion c, List<org.logicng.formulas.Formula> premises,
                        org.logicng.formulas.Formula query) {
        var solver=MiniSat.miniSat(c.factory);
        solver.add(premises); solver.add(query);
        // Tautologies may simplify away; evaluateLit below completes missing positive literals as false.
        for(String name:c.names) solver.add(c.factory.or(c.factory.variable(name),c.factory.literal(name,false)));
        var state=solver.sat(new TimeoutSATHandler(timeoutMillis));
        if(state!=Tristate.TRUE) return new Check(state,Optional.empty());
        var assignment=solver.model();
        Map<String,Boolean> values=new TreeMap<>();
        for(String name:c.names) values.put(name,assignment.evaluateLit(c.factory.variable(name)));
        return new Check(state,Optional.of(new ModelExplorer.World(values)));
    }
    public ModelExplorer.Analysis analyze(Collection<Formula> premises, Formula question, ModelExplorer.Budget budget) {
        Objects.requireNonNull(budget); Objects.requireNonNull(question);
        List<Formula> source=List.copyOf(premises);
        try {
            Conversion c=new Conversion();
            List<org.logicng.formulas.Formula> converted=new ArrayList<>();
            for(Formula p:source) converted.add(c.convert(p,0));
            var q=c.convert(question,0);
            var yes=solve(c,converted,q);
            var no=solve(c,converted,c.factory.not(q));
            if(yes.state==Tristate.UNDEF || no.state==Tristate.UNDEF) return limited("Cooperative SAT timeout");
            verify(yes.witness,source,question,true); verify(no.witness,source,question,false);
            var status=yes.state==Tristate.FALSE && no.state==Tristate.FALSE ? ModelExplorer.Status.INCONSISTENT
                    : yes.state==Tristate.FALSE ? ModelExplorer.Status.PROVEN_FALSE
                    : no.state==Tristate.FALSE ? ModelExplorer.Status.PROVEN_TRUE : ModelExplorer.Status.UNDETERMINED;
            return new ModelExplorer.Analysis(status,List.of(),yes.witness,no.witness,
                    "LogicNG semantic SAT diagnosis; re-evaluated witnesses; no syntactic proof, no world enumeration, no minimal support",false);
        } catch(Limit e) { return limited("SAT conversion node/depth limit"); }
    }
    private static void verify(Optional<ModelExplorer.World> w,List<Formula> premises,Formula q,boolean expected) {
        if(w.isEmpty())return;
        if(premises.stream().anyMatch(p->!w.get().satisfies(p)) || w.get().satisfies(q)!=expected)
            throw new IllegalStateException("Invalid SAT witness rejected");
    }
    private static ModelExplorer.Analysis limited(String reason) {
        return new ModelExplorer.Analysis(ModelExplorer.Status.RESOURCE_LIMIT,List.of(),Optional.empty(),Optional.empty(),reason,false);
    }
}
