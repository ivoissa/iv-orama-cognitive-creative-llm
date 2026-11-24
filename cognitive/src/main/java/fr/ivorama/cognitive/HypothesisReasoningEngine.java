package fr.ivorama.cognitive;

import java.util.*;

/**
 * IVOC-REASON-001: finite, offline, hypothesis-driven investigation.
 * All implications and exhaustive/exclusive assumptions are explicitly supplied.
 * A predicted observation never licenses its converse (no affirming the consequent).
 * The FCI cycle proves/refutes candidates through propositional consequences.
 * No causal claim is empirically verified by this engine.
 */
public final class HypothesisReasoningEngine {
    public enum State { NO_HYPOTHESIS, UNDETERMINED, PROVEN_CONDITIONALLY,
        INCONSISTENT, RESOURCE_LIMIT }
    public record Finding(State state, String answer, String questionId, String cause,
            List<String> possible, List<String> refuted, Optional<String> nextObservation,
            String reason, long version, boolean reopened) {
        public Finding {
            possible=List.copyOf(possible); refuted=List.copyOf(refuted);
            Objects.requireNonNull(nextObservation);
        }
    }
    public record Observation(String signal, boolean value, String source) {}
    private record Candidate(String name, String questionId) {}
    private record Observed(String source, String premise) {}
    private static final int MAX_CANDIDATES=8, MAX_SIGNALS=16, MAX_PREDICTIONS=64;
    private final IntegratedQuestionCycle fci=new IntegratedQuestionCycle();
    private final LinkedHashMap<String,Candidate> candidates=new LinkedHashMap<>();
    private final LinkedHashMap<String,LinkedHashMap<String,Boolean>> predictions=new LinkedHashMap<>();
    private final LinkedHashMap<String,Observed> positive=new LinkedHashMap<>();
    private final LinkedHashMap<String,Observed> negative=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> rulePremises=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> exclusivityPremises=new LinkedHashMap<>();
    private final Set<String> signals=new LinkedHashSet<>();
    private String choicePremise;
    private final String caseName;
    private final boolean exhaustiveUnique;

    /** Exhaustive and mutually exclusive candidate set is an explicit conditional assumption. */
    public HypothesisReasoningEngine(String caseName,boolean exhaustiveUnique) {
        this.caseName=atom(caseName);
        this.exhaustiveUnique=exhaustiveUnique;
    }
    public String caseName() { return caseName; }
    public void candidate(String name) {
        name=atom(name);
        if(candidates.containsKey(name)) throw new IllegalArgumentException("Hypothèse déjà définie : "+name);
        if(candidates.size()>=MAX_CANDIDATES) throw new IllegalStateException("Limite de 8 hypothèses");
        if(choicePremise!=null) { fci.retract(choicePremise); choicePremise=null; }
        for(String other:candidates.keySet()) {
            if(exhaustiveUnique) {
                Formula exclusion=Formula.not(Formula.and(hypothesis(other),hypothesis(name)));
                String key=other+"/"+name;
                exclusivityPremises.put(key,fci.teach(exclusion,"HYPOTHESE DECLAREE : au plus une cause ; "+key));
            }
        }
        String q=fci.ask(hypothesis(name)).id();
        candidates.put(name,new Candidate(name,q));
        predictions.put(name,new LinkedHashMap<>());
        if(exhaustiveUnique) {
            Formula disjunction=null;
            for(String h:candidates.keySet()) {
                disjunction=disjunction==null?hypothesis(h):or(disjunction,hypothesis(h));
            }
            choicePremise=fci.teach(disjunction,"HYPOTHESE DECLAREE : au moins une cause dans la liste de "+caseName);
        }
    }
    public void predicts(String hypothesis,String signal,boolean value) {
        hypothesis=atom(hypothesis); signal=atom(signal);
        var mapping=predictions.get(hypothesis);
        if(mapping==null) throw new IllegalArgumentException("Hypothèse inconnue : "+hypothesis);
        if(mapping.containsKey(signal)) throw new IllegalArgumentException("Prédiction déjà enregistrée : "+hypothesis+"/"+signal);
        if(signals.size()>=MAX_SIGNALS && !signals.contains(signal)) throw new IllegalStateException("Limite de signaux");
        if(rulePremises.size()>=MAX_PREDICTIONS) throw new IllegalStateException("Limite de prédictions");
        Formula conclusion=value?observation(signal):Formula.not(observation(signal));
        String premise=fci.teach(Formula.implies(hypothesis(hypothesis),conclusion),
                "REGLE HYPOTHETIQUE DECLAREE, PAS FAIT OBSERVE : "+hypothesis+" -> "+signal+"="+value);
        mapping.put(signal,value); signals.add(signal); rulePremises.put(hypothesis+"/"+signal,premise);
    }
    public void withdrawPrediction(String hypothesis,String signal) {
        hypothesis=atom(hypothesis); signal=atom(signal);
        var mapping=predictions.get(hypothesis);
        if(mapping==null || !mapping.containsKey(signal)) throw new IllegalArgumentException("Prédiction absente");
        fci.retract(rulePremises.remove(hypothesis+"/"+signal));
        mapping.remove(signal);
    }
    /** Observation is user-provided and not independently authenticated. */
    public void observe(String signal,boolean value,String source) {
        signal=atom(signal);
        if(source==null || source.isBlank()) throw new IllegalArgumentException("Source obligatoire");
        var dest=value?positive:negative;
        if(dest.containsKey(signal)) throw new IllegalArgumentException("Indice déjà déclaré : "+signal+"="+value);
        if(positive.size()+negative.size()>=MAX_SIGNALS*2) throw new IllegalStateException("Limite d'indices");
        String premise=fci.teach(value?observation(signal):Formula.not(observation(signal)),
                "OBSERVATION UTILISATEUR NON VERIFIEE : "+source);
        dest.put(signal,new Observed(source,premise));
    }
    public void withdraw(String signal,boolean value) {
        signal=atom(signal);
        var dest=value?positive:negative;
        Observed observed=dest.get(signal);
        if(observed==null) throw new IllegalArgumentException("Indice absent : "+signal+"="+value);
        fci.retract(observed.premise());
        dest.remove(signal);
    }
    public Finding evaluate() {
        if(candidates.isEmpty()) return new Finding(State.NO_HYPOTHESIS,"UNKNOWN","", "",
                List.of(),List.of(),Optional.empty(),"Déclarer les hypothèses avant de raisonner.",fci.knowledgeVersion(),false);
        // A still-open candidate catalogue is not a substantive diagnosis.
        if(candidates.size()<2) return new Finding(State.UNDETERMINED,"UNKNOWN",
                candidates.values().iterator().next().questionId(),"",List.copyOf(candidates.keySet()),
                List.of(),Optional.empty(),"Déclarer au moins deux hypothèses avant d'interpréter le modèle.",
                fci.knowledgeVersion(),false);
        List<String> possible=new ArrayList<>(), refuted=new ArrayList<>(), proven=new ArrayList<>();
        boolean reopened=false;
        String chosenQuestion="";
        for(Candidate h:candidates.values()) {
            var r=fci.question(h.questionId());
            reopened |= r.reopened();
            if(r.status()==IntegratedQuestionCycle.Status.INCONSISTENT)
                return new Finding(State.INCONSISTENT,"INCONSISTENT",h.questionId(),"",List.of(),List.of(),
                        Optional.empty(),"Prémisses incompatibles : suspendre toute conclusion.",fci.knowledgeVersion(),reopened);
            if(r.status()==IntegratedQuestionCycle.Status.RESOURCE_LIMIT)
                return new Finding(State.RESOURCE_LIMIT,"RESOURCE_LIMIT",h.questionId(),"",List.of(),List.of(),
                        Optional.empty(),"Budget de preuve atteint : aucune conclusion justifiée.",fci.knowledgeVersion(),reopened);
            if(r.answer().isEmpty()) possible.add(h.name());
            else if(r.answer().orElseThrow()) {
                proven.add(h.name()); chosenQuestion=h.questionId();
            } else refuted.add(h.name());
        }
        if(exhaustiveUnique && proven.size()==1 && possible.isEmpty()) {
            String winner=proven.get(0);
            var report=fci.question(candidates.get(winner).questionId());
            String why="Sous les hypothèses déclarées (liste exhaustive/exclusive et prédictions fiables), "
                    +"le raisonnement sur les modèles implique "+winner+". Support FCI="+report.support()
                    +" ; certificat de preuve formelle vérifié="+report.proofChecked()
                    +". Aucun constat causal indépendant.";
            return new Finding(State.PROVEN_CONDITIONALLY,"YES",chosenQuestion,winner,
                    List.of(winner),refuted,Optional.empty(),why,fci.knowledgeVersion(),reopened);
        }
        List<String> remaining=new ArrayList<>(proven); remaining.addAll(possible);
        String why=remaining.isEmpty()?"Aucune hypothèse déclarée compatible : liste ou règles à revoir."
                :"Aucune cause démontrée : une prédiction observée n'implique pas son hypothèse.";
        Optional<String> next=bestDiscriminator(remaining);
        return new Finding(State.UNDETERMINED,"UNKNOWN",chosenQuestion,"",remaining,refuted,next,
                why+(next.isPresent()?" Tester en priorité : "+next.orElseThrow()+".":" Aucun test binaire complet discriminant disponible."),
                fci.knowledgeVersion(),reopened);
    }
    private Optional<String> bestDiscriminator(List<String> remaining) {
        if(remaining.size()<2) return Optional.empty();
        String best=null; int bestScore=0;
        for(String signal:signals) {
            if(positive.containsKey(signal)||negative.containsKey(signal)) continue;
            int yes=0,no=0; boolean all=true;
            for(String name:remaining) {
                Boolean predicted=predictions.get(name).get(signal);
                if(predicted==null) { all=false; break; }
                if(predicted) yes++; else no++;
            }
            if(!all) continue;
            int score=Math.min(yes,no);
            if(score>bestScore || (score==bestScore && score>0 && (best==null||signal.compareTo(best)<0))) {
                best=signal; bestScore=score;
            }
        }
        return Optional.ofNullable(best);
    }
    /** Conservative active-investigation plan based on FCI-compatible hypotheses. */
    public ExperimentChoicePlanner.Plan explore() {
        var snapshot=new LinkedHashMap<String,Map<String,Boolean>>();
        predictions.forEach((h,m)->snapshot.put(h,Map.copyOf(m)));
        return ExperimentChoicePlanner.choose(evaluate(),snapshot,observations());
    }
    /** Provenance-preserving explanation, independently checking candidate models against FCI. */
    public HypothesisEvidenceAudit.Audit explain() {
        var snapshot=new LinkedHashMap<String,Map<String,Boolean>>();
        predictions.forEach((h,m)->snapshot.put(h,Map.copyOf(m)));
        var facts=new ArrayList<HypothesisEvidenceAudit.Evidence>();
        positive.forEach((s,o)->facts.add(new HypothesisEvidenceAudit.Evidence(s,true,o.source())));
        negative.forEach((s,o)->facts.add(new HypothesisEvidenceAudit.Evidence(s,false,o.source())));
        return HypothesisEvidenceAudit.explain(evaluate(),snapshot,facts);
    }
    public List<Observation> observations() {
        List<Observation> result=new ArrayList<>();
        positive.forEach((s,v)->result.add(new Observation(s,true,v.source())));
        negative.forEach((s,v)->result.add(new Observation(s,false,v.source())));
        return List.copyOf(result);
    }
    public String questionId(String hypothesis) { return candidates.get(atom(hypothesis)).questionId(); }
    private static Formula hypothesis(String name) { return Formula.atom("h_"+name); }
    private static Formula observation(String name) { return Formula.atom("s_"+name); }
    private static Formula or(Formula a,Formula b) { return Formula.not(Formula.and(Formula.not(a),Formula.not(b))); }
    private static String atom(String raw) {
        if(raw==null || !raw.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Identifiant attendu : minuscules sans espace (1..40)");
        return raw;
    }
}
