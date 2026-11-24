package fr.ivorama.cognitive;

import java.util.*;

/**
 * IVOC-REASON-004. A bounded, deterministic, minimax observation planner.
 * No priors/probabilities are invented. An unspecified prediction branches both ways.
 * All hypotheses and predictions remain *declared* conditional assumptions.
 */
public final class ExperimentChoicePlanner {
    public record Split(String signal, List<String> ifYes, List<String> ifNo,
                        List<String> unspecified, int worstCase, int guaranteedEliminated) {
        public Split {
            ifYes=List.copyOf(ifYes); ifNo=List.copyOf(ifNo);
            unspecified=List.copyOf(unspecified);
        }
    }
    public record Plan(String status, List<String> viable, List<Split> ranked,
                       Optional<Split> recommended, String reason, long version) {
        public Plan {
            viable=List.copyOf(viable); ranked=List.copyOf(ranked);
            recommended=Objects.requireNonNull(recommended);
        }
        public String render() {
            StringBuilder out=new StringBuilder();
            if(!status.equals("OPEN")) {
                return "REPONSE : "+status+"\nMOTIF : "+reason+"\nVERSION : "+version;
            }
            if(recommended.isEmpty()) {
                return "REPONSE : AUCUN TEST A REDUCTION GARANTIE\nHYPOTHESES : "+viable
                        +"\nMOTIF : "+reason+"\nVERSION : "+version;
            }
            Split best=recommended.orElseThrow();
            out.append("REPONSE : VERIFIER ").append(best.signal())
                    .append(" (élimination garantie : ").append(best.guaranteedEliminated()).append(")")
                    .append("\nSI OUI : ").append(best.ifYes())
                    .append("\nSI NON : ").append(best.ifNo())
                    .append("\nPREDICTIONS ABSENTES : ").append(best.unspecified())
                    .append("\nPIRES CAS : ").append(best.worstCase()).append(" / ").append(viable.size())
                    .append(" hypothèses compatibles\nAUTRES TESTS : ");
            boolean first=true;
            for(Split s:ranked) if(!s.signal().equals(best.signal())) {
                if(!first) out.append(" ; ");
                first=false;
                out.append(s.signal()).append(" (pire=").append(s.worstCase()).append(")");
            }
            if(first) out.append("aucun");
            return out.append("\nSTATUT : stratégie minimax sous prédictions déclarées ; pas de causalité prouvée")
                    .append("\nVERSION : ").append(version).toString();
        }
    }
    private ExperimentChoicePlanner() {}

    public static Plan choose(HypothesisReasoningEngine.Finding finding,
                              Map<String, ? extends Map<String,Boolean>> predictions,
                              List<HypothesisReasoningEngine.Observation> observations) {
        Objects.requireNonNull(finding); Objects.requireNonNull(predictions);
        Objects.requireNonNull(observations);
        if(finding.state()==HypothesisReasoningEngine.State.INCONSISTENT)
            return new Plan("CONTRADICTION — CONCLUSION SUSPENDUE",List.of(),List.of(),Optional.empty(),
                    "Observations ou prémisses incompatibles ; ne proposer aucun test fondé sur cet état.",finding.version());
        if(finding.state()==HypothesisReasoningEngine.State.RESOURCE_LIMIT)
            return new Plan("LIMITE DE CALCUL",List.of(),List.of(),Optional.empty(),
                    "Aucun classement fiable sans évaluation FCI.",finding.version());
        if(finding.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY)
            return new Plan("HYPOTHESE UNIQUE : "+finding.cause(),List.of(finding.cause()),List.of(),Optional.empty(),
                    "Conclusion conditionnelle ; vérification empirique toujours requise.",finding.version());
        if(finding.state()==HypothesisReasoningEngine.State.NO_HYPOTHESIS)
            return new Plan("AUCUNE ENQUETE",List.of(),List.of(),Optional.empty(),
                    "Déclarer plusieurs hypothèses.",finding.version());
        List<String> viable=finding.possible();
        if(viable.size()<2) return new Plan("OPEN",viable,List.of(),Optional.empty(),
                "Moins de deux hypothèses comparables ; aucune séparation mesurable.",finding.version());
        Set<String> already=new HashSet<>();
        for(var obs:observations) already.add(obs.signal());
        SortedSet<String> signals=new TreeSet<>();
        for(String h:viable) signals.addAll(predictions.containsKey(h)?predictions.get(h).keySet():Set.of());
        List<Split> ranked=new ArrayList<>();
        for(String signal:signals) {
            if(already.contains(signal)) continue;
            List<String> yes=new ArrayList<>(),no=new ArrayList<>(),unknown=new ArrayList<>();
            for(String h:viable) {
                Boolean prediction=(predictions.containsKey(h)?predictions.get(h).get(signal):null);
                if(prediction==null) {yes.add(h);no.add(h);unknown.add(h);}
                else if(prediction) yes.add(h);
                else no.add(h);
            }
            int worst=Math.max(yes.size(),no.size());
            ranked.add(new Split(signal,yes,no,unknown,worst,viable.size()-worst));
        }
        // Minimize worst-case candidate count; tie-break by sum of squared branch sizes,
        // then lexical signal name. This is not a probability model.
        ranked.sort(Comparator.comparingInt(Split::worstCase)
                .thenComparingInt(s->s.ifYes().size()*s.ifYes().size()+s.ifNo().size()*s.ifNo().size())
                .thenComparing(Split::signal));
        Optional<Split> best=ranked.stream().filter(s->s.guaranteedEliminated()>0).findFirst();
        return new Plan("OPEN",viable,ranked,best,
                best.isPresent()?"Comparaison conservatrice des branches oui/non.":
                    "Toute observation disponible pourrait laisser toutes les hypothèses compatibles.",finding.version());
    }
}
