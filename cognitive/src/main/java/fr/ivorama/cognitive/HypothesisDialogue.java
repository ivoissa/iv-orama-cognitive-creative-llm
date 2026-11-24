package fr.ivorama.cognitive;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** IVOC-REASON-001: bounded French investigation dialogue; not free-form NLP. */
public final class HypothesisDialogue {
    private HypothesisReasoningEngine engine;
    private NarrativeEvidenceLayer narrative;
    public static boolean isCommand(String input) {
        if(input==null) return false;
        String s=clean(input);
        return s.startsWith("enquete ") || s.startsWith("hypothese ") ||
                s.startsWith("prevoit ") || s.startsWith("indice ") ||
                s.startsWith("retire indice ") || s.startsWith("retire prevision ") ||
                s.startsWith("expression ") || s.startsWith("rapport ") || s.startsWith("retire rapport ") ||
                s.equals("rapports ?") ||
                s.equals("nouvelle enquete") || s.equals("aide enquete") ||
                s.equals("quelle hypothese reste compatible ?") ||
                s.equals("peut-on conclure la cause ?") ||
                s.equals("quelle observation verifier ensuite ?") ||
                s.equals("quel test choisir ?") || s.equals("quelles hypotheses apres le test ?") ||
                s.equals("etat enquete ?") ||
                s.equals("pourquoi cette conclusion ?") ||
                s.equals("quel contre-exemple est possible ?") ||
                s.equals("quelles observations sont indispensables ?") ||
                s.equals("quelles premisses sont contradictoires ?");
    }
    private static String clean(String s) {
        return Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").replaceAll("\\s+"," ");
    }
    private static String[] tokens(String text,int expected) {
        var items=clean(text).split(" ");
        if(items.length!=expected) throw new IllegalArgumentException("Syntaxe incorrecte : "+text);
        return items;
    }
    public StandaloneTechnicalAssistant.Response reply(String input) {
        String s=clean(input);
        try {
            if(s.equals("aide enquete")) return answer("Enquête contrôlée : enquete exclusive <nom> ; hypothese <nom> ; "
                    +"prevoit <hypothese> <indice> oui|non ; indice <nom> oui|non ; "
                    +"retire indice <nom> oui|non ; retire prevision <hypothese> <indice> ; "
                    +"expression signal | affirmation | négation ; rapport id : affirmation. affirmation. ; retire rapport id ; rapports ? ; "
                    +"quelle hypothese reste compatible ? ; peut-on conclure la cause ? ; "
                    +"quelle observation verifier ensuite ? ; quel test choisir ? ; quelles hypotheses apres le test ? ; pourquoi cette conclusion ? ; "
                    +"quel contre-exemple est possible ? ; quelles observations sont indispensables ? ; "
                    +"quelles premisses sont contradictoires ? ; nouvelle enquete. "
                    +"Les hypothèses et règles sont fournies par l'utilisateur, jamais apprises. Le texte libre est interprété seulement via des expressions déclarées.","HELP",true);
            if(s.equals("nouvelle enquete")) { engine=null; narrative=null; return answer("Enquête réinitialisée.","RESET",true); }
            if(s.startsWith("enquete ")) {
                String[] args=tokens(s,3);
                if(!args[1].equals("exclusive")) throw new IllegalArgumentException("Utiliser : enquete exclusive <nom>");
                if(engine!=null) throw new IllegalStateException("Enquête existante, commande 'nouvelle enquete' requise");
                engine=new HypothesisReasoningEngine(args[2],true);
                narrative=new NarrativeEvidenceLayer(engine);
                return answer("Enquête ouverte : "+args[2]+". Postulat déclaré : exactement une hypothèse de la liste est vraie. Liste encore à saisir.","CASE",true);
            }
            if(engine==null) return answer("Commencer par 'enquete exclusive <nom>'.","NEED_CASE",true);
            if(s.startsWith("expression ")) {
                String[] segments=input.substring(input.toLowerCase(Locale.ROOT).indexOf("expression ")+11).split("\\s*\\|\\s*",-1);
                if(segments.length!=3) throw new IllegalArgumentException("Syntaxe : expression signal | phrase positive | phrase négative");
                narrative.register(clean(segments[0]),segments[1],segments[2]);
                return answer("Expressions déclarées pour "+clean(segments[0])+" ; aucun fait créé.","VOCABULARY",true);
            }
            if(s.startsWith("rapport ")) {
                String raw=input.strip(); int colon=raw.indexOf(':');
                if(colon<0) throw new IllegalArgumentException("Syntaxe : rapport identifiant : phrases séparées par un point ou un point-virgule");
                String id=clean(raw.substring(8,colon)).strip();
                var result=narrative.ingest(id,raw.substring(colon+1));
                StringBuilder output=new StringBuilder("RAPPORT "+result.id()+" : "+result.claims().size()+" observation(s) enregistrée(s), "+result.unrecognized().size()+" proposition(s) non interprétée(s).\n");
                for(var c:result.claims()) output.append("OBSERVÉ [déclaration, non vérifié] : ").append(c.signal()).append("=").append(c.value()?"oui":"non").append(" ; source : ").append(c.clause()).append("\n");
                for(String u:result.unrecognized()) output.append("NON INTERPRÉTÉ : ").append(u).append("\n");
                output.append("FCI : questions réévaluées ; aucune cause empirique certifiée.");
                return answer(output.toString(),"REPORT_INGESTED",true);
            }
            if(s.startsWith("retire rapport ")) {
                String[] args=tokens(s,3);
                var removed=narrative.withdraw(args[2]);
                return answer("RAPPORT "+removed.id()+" RETIRÉ : "+removed.claims().size()+" prémisse(s) rétractée(s). Questions FCI recalculées.","REPORT_WITHDRAWN",true);
            }
            if(s.equals("rapports ?")) return answer("Rapports actifs : "+narrative.reportIds()+" ; sens lexicaux : "+narrative.vocabularySize(),"REPORTS",true);
            if(s.startsWith("hypothese ")) {
                String[] a=tokens(s,2); engine.candidate(a[1]);
                return answer("Hypothèse "+a[1]+" déclarée, question FCI "+engine.questionId(a[1])+" ouverte.","CANDIDATE",true);
            }
            if(s.startsWith("prevoit ")) {
                String[] a=tokens(s,4); engine.predicts(a[1],a[2],truth(a[3]));
                return answer("Prédiction déclarée : SI "+a[1]+" ALORS "+a[2]+"="+a[3]+". Pas une observation.","PREDICTION",true);
            }
            if(s.startsWith("indice ")) {
                String[] a=tokens(s,3); engine.observe(a[1],truth(a[2]),"déclaration de l'utilisateur");
                return answer("Indice enregistré : "+a[1]+"="+a[2]+" (source utilisateur, non vérifiée).","OBSERVATION",true);
            }
            if(s.startsWith("retire indice ")) {
                String[] a=tokens(s,4); engine.withdraw(a[2],truth(a[3]));
                return answer("Indice retiré, conclusions FCI recalculées.","RETRACTION",true);
            }
            if(s.startsWith("retire prevision ")) {
                String[] a=tokens(s,4); engine.withdrawPrediction(a[2],a[3]);
                return answer("Prédiction retirée, conclusions FCI recalculées.","RETRACTION",true);
            }
            if(s.equals("quel test choisir ?") || s.equals("quelles hypotheses apres le test ?")) {
                var p=engine.explore();
                return answer(p.render(),"EXPERIMENT_PLAN",true);
            }
            if(s.equals("pourquoi cette conclusion ?") ||
                    s.equals("quel contre-exemple est possible ?") ||
                    s.equals("quelles observations sont indispensables ?") ||
                    s.equals("quelles premisses sont contradictoires ?")) {
                var explanation=engine.explain();
                return answer(explanation.render(),"EVIDENCE_AUDIT",!explanation.conditional());
            }
            if(s.equals("quelle hypothese reste compatible ?") || s.equals("peut-on conclure la cause ?") ||
                    s.equals("quelle observation verifier ensuite ?") ||
                s.equals("quel test choisir ?") || s.equals("quelles hypotheses apres le test ?") || s.equals("etat enquete ?")) {
                var f=engine.evaluate();
                String result;
                if(f.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY) {
                    result="REPONSE : "+f.cause()+" (DÉDUIT CONDITIONNELLEMENT, preuve formelle non certifiée ; causalité non vérifiée).";
                } else if(f.state()==HypothesisReasoningEngine.State.INCONSISTENT) {
                    result="REPONSE : IMPOSSIBLE DE CONCLURE — PRÉMISSES CONTRADICTOIRES.";
                } else if(s.equals("quelle observation verifier ensuite ?")) {
                    result="REPONSE : "+f.nextObservation().map(x->"VÉRIFIER "+x)
                            .orElse("AUCUN TEST DISCRIMINANT COMPLET DISPONIBLE")+".";
                } else result="REPONSE : INDÉTERMINÉ — "+f.possible().size()+" hypothèse(s) encore possible(s).";
                String text=result+"\nFCI : "+f.answer()+" ; état : "+f.state()+" ; réouverture : "+f.reopened()
                        +".\nRDO — compatibles : "+f.possible()+" ; réfutées : "+f.refuted()
                        +" ; prochain indice : "+f.nextObservation().orElse("aucun")+".\n"+f.reason();
                return answer(text,"HYPOTHESIS_REASONING",f.state()!=HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY);
            }
        } catch(IllegalArgumentException | IllegalStateException ex) {
            return answer("Commande refusée : "+ex.getMessage()+". Aucun fait inventé.","HYPOTHESIS_ERROR",true);
        }
        return answer("Commande d'enquête inconnue.","HYPOTHESIS_UNKNOWN",true);
    }
    private static boolean truth(String v) {
        if(v.equals("oui")) return true;
        if(v.equals("non")) return false;
        throw new IllegalArgumentException("Utiliser oui ou non, pas : "+v);
    }
    public HypothesisReasoningEngine engine() { return engine; }
    private static StandaloneTechnicalAssistant.Response answer(String text,String kind,boolean open) {
        return new StandaloneTechnicalAssistant.Response(text,kind,open);
    }
}
