package fr.ivorama.cognitive;

import java.util.*;

/**
 * IVOC-REASON-002: independent, finite evidence audit over the *declared*
 * exclusive candidate model. Does not replace FCI entailment, and never treats
 * user-provided predictions as independently established empirical causation.
 */
public final class HypothesisEvidenceAudit {
    public record Evidence(String signal, boolean value, String source) {
        public Evidence {
            Objects.requireNonNull(signal);
            Objects.requireNonNull(source);
        }
        public String label() { return signal + "=" + (value ? "oui" : "non"); }
    }
    public record Audit(String verdict, List<Evidence> irreducibleEvidence,
            List<String> viableModels, Optional<String> counterexample,
            List<String> exclusions, String explanation, boolean conditional) {
        public Audit {
            irreducibleEvidence=List.copyOf(irreducibleEvidence);
            viableModels=List.copyOf(viableModels);
            counterexample=Objects.requireNonNull(counterexample);
            exclusions=List.copyOf(exclusions);
            Objects.requireNonNull(explanation);
        }
        public String render() {
            StringBuilder s=new StringBuilder("REPONSE : ").append(verdict)
                    .append("\nJUSTIFICATION : ").append(explanation);
            if(!irreducibleEvidence.isEmpty()) {
                s.append("\nINDICES IRREDUCTIBLES : ");
                for(int i=0;i<irreducibleEvidence.size();i++) {
                    if(i>0) s.append(", ");
                    Evidence ev=irreducibleEvidence.get(i);
                    s.append(ev.label()).append(" [source: ").append(ev.source()).append("]");
                }
            }
            if(!exclusions.isEmpty()) s.append("\nEXCLUSIONS : ").append(String.join(" ; ",exclusions));
            counterexample.ifPresent(x->s.append("\nCONTRE-EXEMPLE : ").append(x));
            s.append("\nSTATUT : ").append(conditional ? "MODELE CONDITIONNEL, causalité non vérifiée" : "AUCUNE CONCLUSION CERTIFIEE");
            return s.toString();
        }
    }
    private HypothesisEvidenceAudit() {}

    /** Complete candidate-wise model enumeration for explicit one-of-N assumptions. */
    private static List<String> models(Map<String,? extends Map<String,Boolean>> predictions,
                                       List<Evidence> evidence) {
        List<String> models=new ArrayList<>();
        for(var h:predictions.entrySet()) {
            boolean consistent=true;
            for(Evidence ev:evidence) {
                Boolean expected=h.getValue().get(ev.signal());
                if(expected!=null && expected!=ev.value()) { consistent=false; break; }
            }
            // Unlike an inference from a lone prediction, this is the set of
            // feasible one-of-N worlds, conditional on supplied predictions.
            if(consistent) models.add(h.getKey());
        }
        // Opposing observations are inconsistent regardless of predictions.
        for(Evidence ev:evidence) for(Evidence other:evidence) {
            if(ev.signal().equals(other.signal()) && ev.value()!=other.value()) return List.of();
        }
        return models;
    }
    /** Inclusion-minimal supporting subset (not guaranteed minimum cardinality). */
    private static List<Evidence> reduce(Map<String,? extends Map<String,Boolean>> predictions,
                                         List<Evidence> original, String soleModel) {
        List<Evidence> core=new ArrayList<>(original);
        for(int i=0;i<core.size();) {
            var trial=new ArrayList<>(core); trial.remove(i);
            var possible=models(predictions,trial);
            if(soleModel.equals("<inconsistent>") ? possible.isEmpty() : possible.equals(List.of(soleModel))) {
                core=trial;
            } else i++;
        }
        return core;
    }
    public static Audit explain(HypothesisReasoningEngine.Finding finding,
                                Map<String,? extends Map<String,Boolean>> predictions,
                                List<Evidence> evidence) {
        Objects.requireNonNull(finding);
        List<String> feasible=models(predictions,evidence);
        switch(finding.state()) {
            case NO_HYPOTHESIS, RESOURCE_LIMIT -> {
                return new Audit("INDÉTERMINÉ",List.of(),List.of(),Optional.empty(),List.of(),
                        "Enquête absente ou limite de calcul atteinte.",false);
            }
            case INCONSISTENT -> {
                List<Evidence> core=reduce(predictions,evidence,"<inconsistent>");
                if(feasible.size()!=0) return divergent();
                String cause="Les observations et les hypothèses exclusives ne possèdent aucun modèle commun.";
                String pair="";
                for(Evidence a:core) for(Evidence b:core) {
                    if(a.signal().equals(b.signal()) && a.value()!=b.value()) {
                        pair="On a déclaré à la fois "+a.label()+" et "+b.label()+".";
                        break;
                    }
                }
                return new Audit("CONTRADICTION : conclusion suspendue",core,List.of(),Optional.empty(),List.of(),
                        cause+(pair.isEmpty()?"":" "+pair)+" Indices rapportés, non authentifiés.",false);
            }
            case PROVEN_CONDITIONALLY -> {
                if(!feasible.equals(List.of(finding.cause()))) return divergent();
                List<Evidence> core=reduce(predictions,evidence,finding.cause());
                List<String> eliminations=new ArrayList<>();
                for(String h:predictions.keySet()) {
                    if(h.equals(finding.cause())) continue;
                    for(Evidence ev:core) {
                        Boolean p=predictions.get(h).get(ev.signal());
                        if(p!=null && p!=ev.value()) {
                            eliminations.add(h+" exclue par "+ev.label()+" (prédit "+ev.signal()+"="+(p?"oui":"non")+")");
                            break;
                        }
                    }
                }
                Optional<String> counter=Optional.empty();
                for(Evidence ev:core) {
                    List<Evidence> changed=new ArrayList<>(core); changed.remove(ev);
                    var alternative=models(predictions,changed).stream()
                            .filter(h->!h.equals(finding.cause())).findFirst();
                    if(alternative.isPresent()) {
                        counter=Optional.of("Si l'on retire "+ev.label()+", "+alternative.orElseThrow()
                                +" redevient compatible ; la conclusion n'est plus unique.");
                        break;
                    }
                }
                return new Audit(finding.cause()+" (déduction conditionnelle)",core,feasible,counter,eliminations,
                        "Exactement une hypothèse parmi la liste déclarée ; les indices conservés éliminent les autres. "
                        +"Le sous-ensemble d'indices est irréductible par retrait, pas nécessairement de taille minimale. "
                        +"FCI=YES ; preuve formelle certifiée : non.",true);
            }
            case UNDETERMINED -> {
                // Single candidate is deliberately insufficient to establish exhaustiveness.
                if(feasible.size()!=finding.possible().size() || !new HashSet<>(feasible).equals(new HashSet<>(finding.possible())))
                    return divergent();
                Optional<String> counter=feasible.size()>1 ? Optional.of(
                        "Monde compatible 1 : "+feasible.get(0)+" ; monde compatible 2 : "+feasible.get(1)
                        +". Les mêmes observations conviennent aux deux.") : Optional.empty();
                return new Audit("INDÉTERMINÉ : "+feasible.size()+" hypothèse(s) compatibles",List.of(),feasible,counter,
                        List.of(),"Les observations ne permettent pas une conclusion unique. "
                        +"Il serait incorrect de déduire une cause du seul symptôme qu'elle prédit.",false);
            }
        }
        throw new AssertionError("état d'enquête imprévu");
    }
    private static Audit divergent() {
        return new Audit("AUDIT INCOMPLET : divergences entre modèles et FCI",List.of(),List.of(),
                Optional.empty(),List.of(),"Aucune justification produite avant réconciliation des calculateurs.",false);
    }
}
