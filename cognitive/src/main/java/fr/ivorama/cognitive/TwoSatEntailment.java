package fr.ivorama.cognitive;

import java.util.*;

/**
 * Décision 2-CNF sûre et non exhaustive. Purement sémantique : AUCUN certificat
 * syntaxique ProofChecker n'est produit. Les formules hors fragment retournent empty.
 * Les noms d'atomes sont libres de valeurs a priori et seules les prémisses sont assertées.
 */
public final class TwoSatEntailment {
    private TwoSatEntailment() {}
    public static final int MAX_VARIABLES=50_000;

    private record Lit(int var, boolean positive) {
        Lit negated() { return new Lit(var,!positive); }
        int index() { return var*2+(positive?0:1); }
    }
    private record Clause(Lit a,Lit b) {}
    private record Parsed(List<Clause> clauses,boolean impossible) {}
    private record Solution(Map<String,Boolean> assignment) {}

    /** Non applicable si le langage dépassait le 2-CNF ou la question n'était pas littérale. */
    public static Optional<ModelExplorer.Analysis> analyze(Collection<Formula> premises,
                     Formula question,Collection<Formula> freeExtra) {
        var names=new TreeSet<String>(Formula.atoms(question));
        for(var p:premises) names.addAll(Formula.atoms(p));
        for(var x:freeExtra) names.addAll(Formula.atoms(x));
        if(names.size()>MAX_VARIABLES) return Optional.empty();
        Map<String,Integer> index=new HashMap<>();
        int i=0;for(var n:names) index.put(n,i++);
        List<Lit> target=new ArrayList<>();
        if(!targetLiterals(question,index,target) || target.size()>64) return Optional.empty();
        List<Clause> clauses=new ArrayList<>();
        for(var p:premises) if(!addClauses(p,index,clauses)) return Optional.empty();
        List<String> ordered=new ArrayList<>(names);
        Solution base=solve(clauses,ordered);
        if(base==null) return Optional.of(new ModelExplorer.Analysis(
                ModelExplorer.Status.INCONSISTENT,List.of(),Optional.empty(),Optional.empty(),
                "Inconsistent in 2-SAT fragment; no worlds enumerated",false));
        // To satisfy a conjunction, all targets must hold simultaneously.
        int initial=clauses.size();
        for(Lit q:target) clauses.add(new Clause(q,q));
        Solution yes=solve(clauses,ordered);
        clauses.subList(initial,clauses.size()).clear();
        // To falsify a conjunction, at least one conjunct must be false.
        Solution no=null;
        if(yes==null) no=base; // K is consistent, so every K-model refutes Q.
        else for(Lit q:target) {
            Lit negative=q.negated();
            clauses.add(new Clause(negative,negative));
            no=solve(clauses,ordered);
            clauses.remove(clauses.size()-1);
            if(no!=null) break;
        }
        var status=yes!=null && no!=null ? ModelExplorer.Status.UNDETERMINED
                : yes!=null ? ModelExplorer.Status.PROVEN_TRUE : ModelExplorer.Status.PROVEN_FALSE;
        return Optional.of(new ModelExplorer.Analysis(status,List.of(),
                yes==null?Optional.empty():Optional.of(new ModelExplorer.World(yes.assignment())),
                no==null?Optional.empty():Optional.of(new ModelExplorer.World(no.assignment())),
                "Certified by 2-SAT for literal query; no ProofChecker certificate; no full enumeration",false));
    }
    private static Lit literal(Formula f,Map<String,Integer> map) {
        if(f instanceof Formula.Atom a) return new Lit(map.get(a.name()),true);
        if(f instanceof Formula.Implies imp && imp.premise() instanceof Formula.Atom a
                && imp.conclusion() instanceof Formula.Bottom)
            return new Lit(map.get(a.name()),false);
        return null;
    }
    /** Only literal conjunctions, never silently approximate an arbitrary question. */
    private static boolean targetLiterals(Formula f,Map<String,Integer> map,List<Lit> out) {
        if(f instanceof Formula.And a)
            return targetLiterals(a.left(),map,out) && targetLiterals(a.right(),map,out);
        Lit literal=literal(f,map);
        if(literal==null) return false;
        out.add(literal);
        return true;
    }
    private static boolean addClauses(Formula f,Map<String,Integer> map,List<Clause> output) {
        if(f instanceof Formula.And a)
            return addClauses(a.left(),map,output) && addClauses(a.right(),map,output);
        if(f instanceof Formula.Bottom) { output.add(new Clause(null,null)); return true; }
        Lit unit=literal(f,map);
        if(unit!=null) { output.add(new Clause(unit,unit)); return true; }
        if(f instanceof Formula.Implies implication) {
            if(implication.premise() instanceof Formula.Bottom) return true;
            Lit l=literal(implication.premise(),map);
            if(l==null) return false;
            // p -> (q & r) is the conjunction of p -> q and p -> r.
            if(implication.conclusion() instanceof Formula.And conjunction)
                return addClauses(Formula.implies(implication.premise(),conjunction.left()),map,output)
                    && addClauses(Formula.implies(implication.premise(),conjunction.right()),map,output);
            Lit r=literal(implication.conclusion(),map);
            if(r!=null) { output.add(new Clause(l.negated(),r)); return true; }
        }
        return false;
    }
    @SuppressWarnings("unchecked")
    private static Solution solve(List<Clause> clauses,List<String> atoms) {
        int n=atoms.size(), nodes=n*2;
        List<Integer>[] adj=(List<Integer>[])new List<?>[nodes];
        List<Integer>[] rev=(List<Integer>[])new List<?>[nodes];
        for(int i=0;i<nodes;i++){adj[i]=new ArrayList<>();rev[i]=new ArrayList<>();}
        for(Clause clause:clauses) {
            if(clause.a()==null) return null;
            int a=clause.a().index(),b=clause.b().index();
            edge(adj,rev,a^1,b); edge(adj,rev,b^1,a);
        }
        // Kosaraju itératif : aucune profondeur récursive dépendante des données.
        boolean[] seen=new boolean[nodes];
        int[] order=new int[nodes], stack=new int[nodes], position=new int[nodes];
        int finished=0;
        for(int start=0;start<nodes;start++) {
            if(seen[start]) continue;
            int top=0;stack[0]=start;seen[start]=true;
            while(top>=0) {
                int v=stack[top];
                if(position[v]<adj[v].size()) {
                    int next=adj[v].get(position[v]++);
                    if(!seen[next]) {seen[next]=true;stack[++top]=next;}
                } else {order[finished++]=v;top--;}
            }
        }
        int[] comp=new int[nodes];Arrays.fill(comp,-1);
        int cid=0;
        for(int i=finished-1;i>=0;i--) {
            int start=order[i];if(comp[start]>=0) continue;
            int top=0;stack[0]=start;comp[start]=cid;
            while(top>=0) {
                int v=stack[top--];
                for(int next:rev[v]) if(comp[next]<0) {comp[next]=cid;stack[++top]=next;}
            }
            cid++;
        }
        Map<String,Boolean> values=new LinkedHashMap<>();
        for(int i=0;i<n;i++) {
            if(comp[2*i]==comp[2*i+1]) return null;
            values.put(atoms.get(i),comp[2*i]>comp[2*i+1]);
        }
        return new Solution(values);
    }
    private static void edge(List<Integer>[] adj,List<Integer>[] rev,int a,int b) {
        adj[a].add(b);rev[b].add(a);
    }
}
