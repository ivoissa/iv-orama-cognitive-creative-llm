package fr.ivorama.cognitive;

import java.util.*;

/**
 * Logique propositionnelle finie, sous budget strict. Deux contrats distincts :
 * analyzeFull() énumère TOUS les mondes sur TOUS les atomes ou renvoie RESOURCE_LIMIT.
 * analyze() exploite aussi les composantes indépendantes pour certifier le STATUT ;
 * si l'énumération globale dépasse le budget, possibleWorlds est vide et
 * completeWorlds=false. Les témoins restent des affectations complètes valides.
 * Les consommateurs qui exploitent possibleWorlds doivent appeler analyzeFull().
 */
public final class ModelExplorer {
    private ModelExplorer() {}

    public record Budget(int maxAtoms, long maxAssignments) {
        public Budget {
            if (maxAtoms < 0 || maxAtoms > 20 || maxAssignments < 1)
                throw new IllegalArgumentException("Invalid resource budget");
        }
        public static Budget small() { return new Budget(12, 4096); }
    }

    public enum Status { PROVEN_TRUE, PROVEN_FALSE, UNDETERMINED, INCONSISTENT, RESOURCE_LIMIT }

    public record World(Map<String,Boolean> values) {
        public World { values = Map.copyOf(values); }
        public boolean satisfies(Formula formula) { return formula.evaluate(values); }
    }

    public record Analysis(Status status, List<World> possibleWorlds,
                           Optional<World> trueWitness, Optional<World> falseWitness,
                           String explanation, boolean completeWorlds) {
        public Analysis {
            possibleWorlds = List.copyOf(possibleWorlds);
            Objects.requireNonNull(status); Objects.requireNonNull(trueWitness);
            Objects.requireNonNull(falseWitness); Objects.requireNonNull(explanation);
            if (!completeWorlds && !possibleWorlds.isEmpty())
                throw new IllegalArgumentException("A partial enumeration cannot masquerade as possibleWorlds");
        }
    }

    public static Analysis analyze(Collection<Formula> premises, Formula question, Budget budget) {
        return analyze(premises, question, List.of(), budget);
    }

    /** Diagnostic capable de factoriser les contraintes indépendantes. */
    public static Analysis analyze(Collection<Formula> premises, Formula question,
                                   Collection<Formula> extra, Budget budget) {
        // Compatibility: the historical APIs retain their per-component contract.
        return analyze(premises, question, extra, budget, Long.MAX_VALUE);
    }

    /** Opt-in ceiling on the SUM of enumerated assignments in this invocation.
     * This is not a time/AST/SAT-operation budget. A 2-SAT decision enumerates none.
     * No partial witnesses or projected possibleWorlds escape on exhaustion.
     */
    public static Analysis analyze(Collection<Formula> premises, Formula question,
                                   Budget budget, long maxTotalAssignments) {
        return analyze(premises, question, List.of(), budget, maxTotalAssignments);
    }
    public static Analysis analyze(Collection<Formula> premises, Formula question,
                                   Collection<Formula> extra, Budget budget,
                                   long maxTotalAssignments) {
        checkInputs(premises,question,extra,budget);
        if (maxTotalAssignments < 1) throw new IllegalArgumentException("Positive cumulative assignment budget required");
        SortedSet<String> names = allNames(premises,question,extra);
        if (withinBudget(names.size(),budget) && (1L << names.size()) <= maxTotalAssignments)
            return enumerateFull(premises,question,names,budget);
        // Le raccourci 2-SAT ne contourne pas un budget explicitement restrictif.
        if (budget.maxAtoms()>=12 && budget.maxAssignments()>=4096) {
            var twoSat=TwoSatEntailment.analyze(premises,question,extra);
            if(twoSat.isPresent()) return twoSat.get();
        }
        return analyzeComponents(premises,question,names,budget,maxTotalAssignments);
    }

    /** Énumération exacte requise par RDO, partitions et gains informationnels. */
    public static Analysis analyzeFull(Collection<Formula> premises, Formula question, Budget budget) {
        return analyzeFull(premises,question,List.of(),budget);
    }
    public static Analysis analyzeFull(Collection<Formula> premises, Formula question,
                                       Collection<Formula> extra, Budget budget) {
        return analyzeFull(premises,question,extra,budget,Long.MAX_VALUE);
    }
    public static Analysis analyzeFull(Collection<Formula> premises, Formula question,
                                       Budget budget, long maxTotalAssignments) {
        return analyzeFull(premises,question,List.of(),budget,maxTotalAssignments);
    }
    public static Analysis analyzeFull(Collection<Formula> premises, Formula question,
                                       Collection<Formula> extra, Budget budget,
                                       long maxTotalAssignments) {
        checkInputs(premises,question,extra,budget);
        if (maxTotalAssignments < 1) throw new IllegalArgumentException("Positive cumulative assignment budget required");
        SortedSet<String> names=allNames(premises,question,extra);
        if (!withinBudget(names.size(),budget) || (1L << names.size()) > maxTotalAssignments)
            return limited("Full possibleWorlds enumeration exceeds budget; no projected worlds substituted");
        return enumerateFull(premises,question,names,budget);
    }

    private static void checkInputs(Collection<Formula> premises, Formula question,
                                    Collection<Formula> extra, Budget budget) {
        Objects.requireNonNull(premises);Objects.requireNonNull(question);
        Objects.requireNonNull(extra);Objects.requireNonNull(budget);
        for (Formula p:premises) Objects.requireNonNull(p);
        for (Formula e:extra) Objects.requireNonNull(e);
    }
    private static SortedSet<String> allNames(Collection<Formula> premises,Formula question,
                                               Collection<Formula> extra) {
        SortedSet<String> names=new TreeSet<>(Formula.atoms(question));
        for (Formula p:premises) names.addAll(Formula.atoms(p));
        for (Formula f:extra) names.addAll(Formula.atoms(f));
        return names;
    }
    private static boolean withinBudget(int n,Budget budget) {
        return n<=budget.maxAtoms() && (1L<<n)<=budget.maxAssignments();
    }
    private static Analysis limited(String explanation) {
        return new Analysis(Status.RESOURCE_LIMIT,List.of(),Optional.empty(),Optional.empty(),explanation,false);
    }

    private static Analysis enumerateFull(Collection<Formula> premises,Formula question,
                                          SortedSet<String> names,Budget budget) {
        List<String> atoms=new ArrayList<>(names);
        long count=1L<<atoms.size();
        List<World> worlds=new ArrayList<>(); World positive=null,negative=null;
        for(long mask=0;mask<count;mask++) {
            Map<String,Boolean> assignment=assignment(atoms,mask);
            if (!satisfiesAll(premises,assignment)) continue;
            World w=new World(assignment);worlds.add(w);
            if (question.evaluate(assignment)) {if(positive==null) positive=w;}
            else if(negative==null) negative=w;
        }
        Status status=classify(worlds.isEmpty(),positive!=null,negative!=null);
        return new Analysis(status,worlds,Optional.ofNullable(positive),Optional.ofNullable(negative),
                "Full enumeration of "+count+" assignments, "+worlds.size()+" compatible worlds",true);
    }
    private static Map<String,Boolean> assignment(List<String> atoms,long mask) {
        Map<String,Boolean> a=new LinkedHashMap<>();
        for(int i=0;i<atoms.size();i++) a.put(atoms.get(i),(mask&(1L<<i))!=0);
        return a;
    }
    private static boolean satisfiesAll(Collection<Formula> premises,Map<String,Boolean> assignment) {
        for(Formula p:premises) if(!p.evaluate(assignment)) return false;
        return true;
    }
    private static Status classify(boolean empty,boolean yes,boolean no) {
        return empty?Status.INCONSISTENT:yes&&no?Status.UNDETERMINED:
                yes?Status.PROVEN_TRUE:Status.PROVEN_FALSE;
    }

    /**
     * Each premise connects exactly the variables occurring in that premise;
     * the target Q joins its own atoms. Extras are free variables, NOT assumptions
     * and do not artificially couple independent premise components.
     * Any inconsistent component invalidates the WHOLE context, even if Q is elsewhere.
     * maxAssignments bounds EACH component. The opt-in overload also bounds
     * their cumulative enumeration. Neither contract enumerates global worlds.
     */
    /**
     * Conservatively extracts premises connected to a finite set of target formulas.
     * This is for a *local* world enumeration, never an assertion of the global
     * world's count. The caller must separately certify global consistency.
     */
    public static List<Formula> relevantPremises(Collection<Formula> premises,
                                                  Collection<Formula> targets) {
        Objects.requireNonNull(premises);Objects.requireNonNull(targets);
        Set<String> scope=new HashSet<>();
        for(Formula target:targets) scope.addAll(Formula.atoms(Objects.requireNonNull(target)));
        List<Formula> selected=new ArrayList<>();
        List<Formula> remaining=new ArrayList<>(premises);
        boolean changed;
        do {
            changed=false;
            Iterator<Formula> cursor=remaining.iterator();
            while(cursor.hasNext()) {
                Formula p=cursor.next();
                Set<String> atoms=Formula.atoms(p);
                if(!Collections.disjoint(scope,atoms)) {
                    selected.add(p);cursor.remove();
                    if(scope.addAll(atoms))changed=true;
                }
            }
        } while(changed);
        return List.copyOf(selected);
    }

    private static Analysis analyzeComponents(Collection<Formula> premises,Formula question,
                                               SortedSet<String> all,Budget budget,
                                               long maxTotalAssignments) {
        List<Formula> prem=List.copyOf(premises);
        for(Formula p:prem) if(Formula.atoms(p).isEmpty() && !p.evaluate(Map.of()))
            return new Analysis(Status.INCONSISTENT,List.of(),Optional.empty(),Optional.empty(),
                    "Contradiction in a constant premise",false);
        Map<String,Set<String>> graph=new TreeMap<>();
        for(String a:all) graph.put(a,new TreeSet<>());
        for(Formula p:prem) connect(graph,Formula.atoms(p));
        SortedSet<String> questionAtoms=Formula.atoms(question);
        connect(graph,questionAtoms);
        List<List<String>> components=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(String first:graph.keySet()) {
            if(!seen.add(first))continue;
            List<String> component=new ArrayList<>(); Deque<String> todo=new ArrayDeque<>();todo.add(first);
            while(!todo.isEmpty()) {
                String v=todo.removeFirst();component.add(v);
                for(String adjacent:graph.get(v)) if(seen.add(adjacent))todo.addLast(adjacent);
            }
            Collections.sort(component);components.add(component);
        }
        Map<String,Boolean> common=new TreeMap<>();
        Map<String,Boolean> posPart=null, negPart=null;
        long visited=0;
        if(components.size()>100_000)
            return limited("Too many independent components (>100000)");
        for(List<String> component:components) {
            if(component.size()>budget.maxAtoms())
                return limited("A connected component exceeds the atom budget ("+component.size()+")");
            long count=1L<<component.size();
            if(count>budget.maxAssignments())
                return limited("Independent component exceeds per-component assignment budget");
            if(count > maxTotalAssignments - visited)
                return limited("Cumulative assignment budget exceeded: visited=" + visited +
                        ", next=" + count + ", maximum=" + maxTotalAssignments +
                        "; no partial witnesses returned");
            visited+=count;
            Set<String> scope=new HashSet<>(component);
            List<Formula> local=new ArrayList<>();
            for(Formula p:prem) {
                Set<String> in=Formula.atoms(p);
                if(!in.isEmpty() && scope.containsAll(in))local.add(p);
            }
            boolean target=!questionAtoms.isEmpty()&&scope.containsAll(questionAtoms);
            Map<String,Boolean> first=null,yes=null,no=null;
            for(long mask=0;mask<count;mask++) {
                Map<String,Boolean> a=assignment(component,mask);
                if(!satisfiesAll(local,a))continue;
                if(first==null)first=a;
                if(target) {
                    if(question.evaluate(a)) {if(yes==null)yes=a;}
                    else if(no==null)no=a;
                }
            }
            if(first==null)
                return new Analysis(Status.INCONSISTENT,List.of(),Optional.empty(),Optional.empty(),
                        "Globally inconsistent: an independent premise component has no model",false);
            common.putAll(first);
            if(target) {posPart=yes;negPart=no;}
        }
        if(questionAtoms.isEmpty()) {
            if(question.evaluate(Map.of()))posPart=Map.of(); else negPart=Map.of();
        }
        World positive=makeWitness(common,posPart); World negative=makeWitness(common,negPart);
        Status status=classify(false,positive!=null,negative!=null);
        return new Analysis(status,List.of(),Optional.ofNullable(positive),Optional.ofNullable(negative),
                "Certified via "+components.size()+" independent components, "+visited+
                " local assignments; global possibleWorlds NOT enumerated",false);
    }
    private static void connect(Map<String,Set<String>> graph,Set<String> names) {
        if(names.isEmpty())return;
        String first=names.iterator().next();
        for(String name:names) {graph.get(first).add(name);graph.get(name).add(first);}
    }
    private static World makeWitness(Map<String,Boolean> base,Map<String,Boolean> questionPart) {
        if(questionPart==null)return null;
        Map<String,Boolean> full=new LinkedHashMap<>(base);full.putAll(questionPart);
        return new World(full);
    }
}
