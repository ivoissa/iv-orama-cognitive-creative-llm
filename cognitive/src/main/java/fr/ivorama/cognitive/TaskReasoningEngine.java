package fr.ivorama.cognitive;

import java.text.Normalizer;
import java.util.*;

/**
 * IVOC-TASK-002. Explicit offline plan/evidence model over a finite task graph.
 * An action definition is NOT an implication: its output becomes a premise only
 * after an explicit user attestation, with all prerequisites already supported.
 * Retraction of a dependency cascades through attested actions and reopens FCI.
 * This is not an executor, a natural-language parser or independent verification.
 */
public final class TaskReasoningEngine {
    public enum State { ESTABLISHED_CONDITIONALLY, PLAN_AVAILABLE, NEED_OBSERVATION,
        CONTRADICTED, INCONSISTENT, CYCLIC_PLAN, RESOURCE_LIMIT }
    public record Action(String id, String effect, List<String> prerequisites) {
        public Action { prerequisites=List.copyOf(prerequisites); }
    }
    public record Plan(State state, String questionId, String goal, String fci, boolean reopened,
            List<String> orderedActions, List<String> missingObservations,
            String explanation, long version) {
        public Plan { orderedActions=List.copyOf(orderedActions);
            missingObservations=List.copyOf(missingObservations); }
    }
    public record Recorded(long version, String operation, String detail) {}
    private record Fact(String premise, boolean positive, String source) {}
    private record Completion(String premise, Set<String> dependencies) {
        private Completion { dependencies=Set.copyOf(dependencies); }
    }
    private static final int MAX_ACTIONS=48, MAX_ATOMS=48, MAX_REQUIREMENTS=12;
    private final IntegratedQuestionCycle fci = new IntegratedQuestionCycle();
    private final Map<String,Action> actions=new LinkedHashMap<>();
    private final Map<String,Fact> positive=new LinkedHashMap<>();
    private final Map<String,Fact> negative=new LinkedHashMap<>();
    private final Map<String,Completion> completions=new LinkedHashMap<>();
    private final List<Recorded> history=new ArrayList<>();
    private String goal, questionId;
    private long seq;

    public void setGoal(String predicate) {
        predicate=atom(predicate);
        if (goal!=null && !goal.equals(predicate)) throw new IllegalStateException(
                "Objectif déjà fixé ; créer une nouvelle session pour une autre tâche.");
        if (goal!=null) return;
        goal=predicate;
        questionId=fci.ask(Formula.atom(goal)).id();
        log("GOAL",goal+" FCI="+questionId);
    }
    public void define(String id, String output, String... requires) {
        id=atom(id); output=atom(output); Objects.requireNonNull(requires);
        if(actions.size()>=MAX_ACTIONS) throw new IllegalStateException("Limite d'actions atteinte");
        if(requires.length>MAX_REQUIREMENTS) throw new IllegalArgumentException("Trop de prérequis");
        if(actions.containsKey(id)) throw new IllegalArgumentException("Action déjà définie : "+id);
        for(Action a: actions.values()) if(a.effect().equals(output))
            throw new IllegalArgumentException("Sortie déjà produite par "+a.id());
        List<String> pre=new ArrayList<>();
        for(String v:requires) {
            String req=atom(v);
            if(pre.contains(req)) throw new IllegalArgumentException("Prérequis dupliqué : "+req);
            pre.add(req);
        }
        actions.put(id,new Action(id,output,pre));
        log("ACTION_DECLARED",id+" -> "+output+" si "+pre);
    }
    public void observe(String predicate, boolean value, String source) {
        predicate=atom(predicate);
        if(source==null || source.isBlank()) throw new IllegalArgumentException("Source requise");
        if(positive.size()+negative.size()+completions.size()>=MAX_ATOMS)
            throw new IllegalStateException("Limite de faits atteinte");
        var map=value?positive:negative;
        if(map.containsKey(predicate)) throw new IllegalArgumentException("Observation déjà enregistrée");
        String premise=fci.teach(value?Formula.atom(predicate):Formula.not(Formula.atom(predicate)),
                "OBSERVATION DECLAREE : "+source);
        map.put(predicate,new Fact(premise,value,source));
        log("OBSERVATION_DECLARED",predicate+"="+value+" : "+source);
    }
    /** Remove one direct user observation; derived attestations depending on it are invalidated. */
    public void withdraw(String predicate, boolean value) {
        predicate=atom(predicate);
        var map=value?positive:negative;
        Fact fact=map.remove(predicate);
        if(fact==null) throw new IllegalArgumentException("Observation absente : "+predicate+"="+value);
        fci.retract(fact.premise());
        log("OBSERVATION_RETRACTED",predicate+"="+value);
        invalidateUnsupported();
    }
    /** Explicit confirmation, not execution: all inputs must have positive support now. */
    public void attest(String id, String source) {
        id=atom(id);
        if(source==null || source.isBlank()) throw new IllegalArgumentException("Source requise");
        Action action=actions.get(id);
        if(action==null) throw new IllegalArgumentException("Action inconnue : "+id);
        if(completions.containsKey(id)) throw new IllegalArgumentException("Action déjà attestée : "+id);
        if(negative.containsKey(action.effect())) throw new IllegalStateException(
                "Sortie contredite par une observation négative : "+action.effect());
        LinkedHashSet<String> dep=new LinkedHashSet<>();
        for(String prerequisite:action.prerequisites()) {
            if(negative.containsKey(prerequisite))
                throw new IllegalStateException("Prérequis contredit : "+prerequisite);
            Fact observed=positive.get(prerequisite);
            if(observed!=null) { dep.add(observed.premise()); continue; }
            Completion completed=completionForEffect(prerequisite);
            if(completed!=null) { dep.add(completed.premise()); continue; }
            throw new IllegalStateException("Prérequis non établi : "+prerequisite);
        }
        String premise=fci.teach(Formula.atom(action.effect()),
                "SORTIE ATTESTEE PAR UTILISATEUR : "+id+" : "+source);
        completions.put(id,new Completion(premise,dep));
        log("ACTION_ATTESTED",id+" (déclarée, NON exécutée par le moteur)");
    }
    public void withdrawAttestation(String id) {
        Completion c=completions.remove(atom(id));
        if(c==null) throw new IllegalArgumentException("Attestation absente : "+id);
        fci.retract(c.premise());
        log("ATTESTATION_RETRACTED",id);
        invalidateUnsupported();
    }
    public Plan evaluate() {
        if(goal==null) throw new IllegalStateException("Définis d'abord un objectif");
        var report=fci.question(questionId);
        if(report.status()==IntegratedQuestionCycle.Status.INCONSISTENT)
            return plan(State.INCONSISTENT,report,List.of(),List.of(),
                    "Prémisses contradictoires : aucune solution justifiée.");
        if(report.status()==IntegratedQuestionCycle.Status.RESOURCE_LIMIT)
            return plan(State.RESOURCE_LIMIT,report,List.of(),List.of(),
                    "Limite du moteur de preuves : aucune conclusion déduite.");
        if(report.answer().isPresent() && report.answer().orElseThrow())
            return plan(State.ESTABLISHED_CONDITIONALLY,report,List.of(),List.of(),
                    "Objectif soutenu par des déclarations ; non vérifié indépendamment.");
        if(negative.containsKey(goal))
            return plan(State.CONTRADICTED,report,List.of(),List.of(),
                    "Objectif explicitement contredit ; examiner l'observation négative.");
        LinkedHashSet<String> requiredActions=new LinkedHashSet<>(), missing=new LinkedHashSet<>();
        try {
            discover(goal,new LinkedHashSet<>(),requiredActions,missing);
        } catch(Cycle ex) {
            return plan(State.CYCLIC_PLAN,report,List.of(),List.copyOf(missing),
                    "Cycle détecté : "+ex.getMessage()+". Aucune exécution supposée.");
        }
        boolean blocked=missing.stream().anyMatch(x->x.endsWith("(contredit)"));
        State state=blocked?State.CONTRADICTED:missing.isEmpty()?State.PLAN_AVAILABLE:State.NEED_OBSERVATION;
        String explanation=blocked?"Plan bloqué par une observation négative : "+missing+". Aucune étape ne peut être validée."
                :missing.isEmpty()?"Plan théorique disponible. Aucune étape n'est exécutée."
                :"Observation(s) nécessaire(s) : "+missing+". Aucune étape n'est exécutée.";
        return plan(state,report,List.copyOf(requiredActions),List.copyOf(missing),explanation);
    }
    public List<Recorded> journal() { return List.copyOf(history); }
    public String questionId() { return questionId; }
    public boolean isAttested(String id) { return completions.containsKey(id); }

    private Plan plan(State state,IntegratedQuestionCycle.Report r,List<String> steps,
                      List<String> missing,String why) {
        String result=r.status()==IntegratedQuestionCycle.Status.INCONSISTENT?"INCONSISTENT"
                :r.status()==IntegratedQuestionCycle.Status.RESOURCE_LIMIT?"RESOURCE_LIMIT"
                :r.answer().map(x->x?"YES":"NO").orElse("UNKNOWN");
        return new Plan(state,questionId,goal,result,r.reopened(),steps,missing,why,fci.knowledgeVersion());
    }
    private void discover(String fact,LinkedHashSet<String> stack,
                          LinkedHashSet<String> ordered,LinkedHashSet<String> missing) {
        if(positive.containsKey(fact) || completionForEffect(fact)!=null) return;
        if(negative.containsKey(fact)) { missing.add(fact+" (contredit)"); return; }
        if(!stack.add(fact)) throw new Cycle(String.join(" -> ",stack)+" -> "+fact);
        Action producer=producer(fact);
        if(producer==null) missing.add(fact);
        else {
            for(String prerequisite:producer.prerequisites()) discover(prerequisite,stack,ordered,missing);
            ordered.add(producer.id());
        }
        stack.remove(fact);
    }
    private Action producer(String fact) {
        for(Action a:actions.values()) if(a.effect().equals(fact)) return a;
        return null;
    }
    private Completion completionForEffect(String fact) {
        for(Map.Entry<String,Completion> e:completions.entrySet())
            if(actions.get(e.getKey()).effect().equals(fact)) return e.getValue();
        return null;
    }
    private void invalidateUnsupported() {
        boolean changed;
        do {
            changed=false;
            Set<String> live=new HashSet<>();
            positive.values().forEach(f->live.add(f.premise()));
            completions.values().forEach(c->live.add(c.premise()));
            for(var entry:new ArrayList<>(completions.entrySet())) {
                if(!live.containsAll(entry.getValue().dependencies())) {
                    completions.remove(entry.getKey());
                    fci.retract(entry.getValue().premise());
                    log("ATTESTATION_INVALIDATED",entry.getKey()+" : support retiré");
                    changed=true;
                }
            }
        } while(changed);
    }
    private void log(String operation,String detail) { history.add(new Recorded(++seq,operation,detail)); }
    private static String atom(String value) {
        Objects.requireNonNull(value);
        String cleaned=Normalizer.normalize(value.trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFC);
        if(!cleaned.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException(
                "Identifiant attendu : lettres a-z, chiffres, '_', commençant par une lettre : "+value);
        return cleaned;
    }
    private static final class Cycle extends RuntimeException { Cycle(String detail) { super(detail); } }
}
