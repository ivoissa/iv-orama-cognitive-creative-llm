package fr.ivorama.cognitive;

import java.util.*;

/** Opt-in proof construction, never SAT certification. All caches are invocation/context local. */
final class IndexedProofSearch {
    private IndexedProofSearch() {}
    private static final class Timing {
        final long start=System.nanoTime();
        long search, verification;
        boolean checked;
    }
    static ProofSearch.MeasuredSearch measure(Collection<Formula> premises, Formula goal,
                                               int capacity, long maximum) {
        Timing t=new Timing();
        var outcome=find(premises,goal,capacity,maximum,t);
        if(!t.checked)t.search=System.nanoTime()-t.start;
        return new ProofSearch.MeasuredSearch(outcome,t.search,t.verification);
    }
    private static final class Limit extends RuntimeException {
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }
    private static final class Work {
        final long maximum;
        final int capacity;
        long used;
        boolean limited;
        Work(int capacity, long maximum) { this.capacity=capacity; this.maximum=maximum; }
        void tick() { if(used==maximum) throw new Limit(); used++; }
    }
    static ProofSearch.SearchOutcome find(Collection<Formula> premises, Formula goal,
                                          int capacity, long maximum) {
        return find(premises,goal,capacity,maximum,null);
    }
    private static ProofSearch.SearchOutcome find(Collection<Formula> premises, Formula goal,
                                                  int capacity, long maximum, Timing timing) {
        Objects.requireNonNull(premises); Objects.requireNonNull(goal);
        if(capacity<1 || maximum<1) throw new IllegalArgumentException("Positive proof-search budgets required");
        Work w=new Work(capacity,maximum);
        try {
            List<Formula> context=List.copyOf(premises);
            Proof proof;
            if(context.size()==1 && context.getFirst().equals(goal)) {
                w.tick();w.tick();proof=new Proof.Assumption(goal);
            } else proof=prove(new Closure(context,w),goal,0,w);
            if(proof!=null) {
                if(!(proof instanceof Proof.Assumption))proof=reshape(proof,w,new IdentityHashMap<>());
                // An independent, separately bounded check is mandatory even for forward derivations.
                long checkStart=timing==null?0:System.nanoTime();
                if(timing!=null){timing.search=checkStart-timing.start;timing.checked=true;}
                var checked=ProofChecker.verify(proof,context);
                if(timing!=null)timing.verification=System.nanoTime()-checkStart;
                if(checked.valid() && goal.equals(checked.conclusion()))
                    return new ProofSearch.SearchOutcome(ProofSearch.SearchStatus.FOUND,Optional.of(proof),w.used);
                w.limited=true;
            }
            return new ProofSearch.SearchOutcome(w.limited ? ProofSearch.SearchStatus.RESOURCE_LIMIT :
                    ProofSearch.SearchStatus.NOT_FOUND,Optional.empty(),w.used);
        } catch(Limit exhausted) {
            return new ProofSearch.SearchOutcome(ProofSearch.SearchStatus.RESOURCE_LIMIT,Optional.empty(),w.used);
        }
    }
    private static Proof prove(Closure c, Formula goal, int depth, Work w) {
        w.tick();
        Proof direct=c.known.get(goal);
        if(direct!=null) return direct;
        c.saturate(goal);
        direct=c.known.get(goal);
        if(direct!=null) return direct;
        if(goal instanceof Formula.And a && depth<8) {
            // Same context/closure reused for sibling goals. No assumption is added here.
            Proof l=prove(c,a.left(),depth+1,w);
            if(l!=null) {
                Proof r=prove(c,a.right(),depth+1,w);
                if(r!=null) return new Proof.AndIntroduction(l,r);
            }
        }
        if(goal instanceof Formula.Implies a && depth<4) {
            // A new assumption always gets a NEW closure: it must not leak to a sibling/other call.
            List<Formula> expanded=new ArrayList<>(c.premises);
            expanded.add(a.premise());
            Proof body=prove(new Closure(expanded,w),a.conclusion(),depth+1,w);
            if(body!=null) return new Proof.ImplicationIntroduction(a.premise(),body);
        }
        return null;
    }
    /** Coarse cuts reduce long dependency-union chains without changing the checker.
     * Optional work must never consume a found proof's remaining budget and lose it.
     */
    private static Proof reshape(Proof p, Work w, IdentityHashMap<Proof,Proof> memo) {
        Proof old=memo.get(p);if(old!=null)return old;
        if(w.maximum-w.used<4)return p;
        w.tick();
        Proof result=p;
        if(p instanceof Proof.AndIntroduction a) {
            result=new Proof.AndIntroduction(reshape(a.left(),w,memo),reshape(a.right(),w,memo));
        } else if(p instanceof Proof.ImplicationIntroduction a) {
            result=new Proof.ImplicationIntroduction(a.temporaryAssumption(),reshape(a.derivation(),w,memo));
        } else if(p instanceof Proof.ModusPonens) {
            List<Proof.Assumption> reversed=new ArrayList<>();Proof seed=p;
            while(seed instanceof Proof.ModusPonens mp && mp.implication() instanceof Proof.Assumption rule
                    && rule.formula() instanceof Formula.Implies) {
                if(w.maximum-w.used<4)return p;
                w.tick();reversed.add(rule);seed=mp.antecedent();
            }
            if(reversed.size()>=32 && seed instanceof Proof.Assumption initial &&
                    w.maximum-w.used>=4L*reversed.size()+32) {
                Collections.reverse(reversed);Formula current=initial.formula();boolean valid=true;
                for(var rule:reversed) {
                    w.tick();var arrow=(Formula.Implies)rule.formula();
                    if(!current.equals(arrow.premise())){valid=false;break;}
                    current=arrow.conclusion();
                }
                if(valid)result=balanced(reversed,0,reversed.size(),seed,w);
            }
        }
        memo.put(p,result);return result;
    }
    private static Proof balanced(List<Proof.Assumption> rules,int start,int end,Proof seed,Work w) {
        if(end-start<=16) {
            Proof p=seed;for(int i=start;i<end;i++){w.tick();p=new Proof.ModusPonens(p,rules.get(i));}return p;
        }
        w.tick();int mid=(start+end)/2;
        Formula cut=((Formula.Implies)rules.get(mid).formula()).premise();
        Proof left=balanced(rules,start,mid,seed,w);
        Proof right=balanced(rules,mid,end,new Proof.Assumption(cut),w);
        return new Proof.ModusPonens(left,new Proof.ImplicationIntroduction(cut,right));
    }
    private static final class Rule {
        final Formula.Implies arrow;final Proof proof;boolean fired;
        Rule(Formula.Implies arrow,Proof proof){this.arrow=arrow;this.proof=proof;}
    }
    private static final class Closure {
        final List<Formula> premises;
        final Work work;
        final Map<Formula,Proof> known=new LinkedHashMap<>();
        final Deque<Formula> agenda=new ArrayDeque<>();
        final Map<Formula,List<Rule>> watchers=new HashMap<>();
        Closure(List<Formula> premises, Work work) {
            this.premises=premises;this.work=work;
            for(Formula f:premises) if(!add(f,new Proof.Assumption(f))) {
                // As in the reference, do not discard input premises to fit the capacity.
                if(!known.containsKey(f)) throw new Limit();
            }
        }
        boolean add(Formula formula, Proof proof) {
            work.tick();
            if(known.containsKey(formula)) return false;
            if(known.size()>=work.capacity) { work.limited=true;return false; }
            known.put(formula,proof);agenda.addLast(formula);return true;
        }
        void saturate(Formula goal) {
            while(!agenda.isEmpty() && !known.containsKey(goal)) {
                work.tick();
                Formula f=agenda.removeFirst();Proof p=known.get(f);
                if(f instanceof Formula.And a) {
                    add(a.left(),new Proof.AndLeft(p));
                    add(a.right(),new Proof.AndRight(p));
                }
                if(f instanceof Formula.Implies a) {
                    // Ordered/simple chains can fire immediately without allocating watchers.
                    Proof ready=known.get(a.premise());
                    if(ready!=null) {
                        work.tick();add(a.conclusion(),new Proof.ModusPonens(ready,p));
                    } else {
                    Rule rule=new Rule(a,p);
                    if(!(a.premise() instanceof Formula.And)) {
                        work.tick();watchers.computeIfAbsent(a.premise(),k->new ArrayList<>()).add(rule);
                    } else {
                    Set<Formula> needs=new LinkedHashSet<>();
                    indexNeeds(a.premise(),needs,0);
                    for(Formula need:needs) {work.tick();watchers.computeIfAbsent(need,k->new ArrayList<>()).add(rule);}
                    }
                    fire(rule);
                    }
                }
                for(Rule rule:watchers.getOrDefault(f,List.of())) {work.tick();fire(rule);}
            }
        }
        void indexNeeds(Formula f, Set<Formula> needs, int depth) {
            work.tick();
            needs.add(f);
            if(f instanceof Formula.And a) {
                if(depth>=32) {work.limited=true;return;}
                indexNeeds(a.left(),needs,depth+1);indexNeeds(a.right(),needs,depth+1);
            }
        }
        Proof antecedent(Formula f, int depth) {
            work.tick();
            Proof existing=known.get(f);
            if(existing!=null) return existing;
            if(f instanceof Formula.And a && depth<32) {
                Proof l=antecedent(a.left(),depth+1);
                if(l!=null) {Proof r=antecedent(a.right(),depth+1);
                    if(r!=null) return new Proof.AndIntroduction(l,r);}
            }
            return null;
        }
        void fire(Rule rule) {
            work.tick();
            if(rule.fired) return;
            Proof a=antecedent(rule.arrow.premise(),0);
            if(a!=null) {
                rule.fired=true;
                add(rule.arrow.conclusion(),new Proof.ModusPonens(a,rule.proof));
            }
        }
    }
}
