package fr.ivorama.cognitive;

import java.util.*;

/** Three explicitly displayed questions, plus adversarial/invariant/differential tests. */
public final class HypothesisEvidenceAuditTest {
    private static int checks;
    private static void check(boolean pass,String why) {
        checks++;
        if(!pass) throw new AssertionError("Echec "+checks+": "+why);
    }
    private static void tell(StandaloneTechnicalAssistant bot,String command) {
        var r=bot.reply(command);
        check(!r.kind().contains("ERROR") && !r.text().startsWith("Commande refusée"),
                "commande "+command+" : "+r.text());
    }
    private static StandaloneTechnicalAssistant.Response ask(StandaloneTechnicalAssistant bot,String question) {
        var r=bot.reply(question);
        check(r.kind().equals("EVIDENCE_AUDIT"),"moteur d'explication invoqué : "+r.text());
        return r;
    }
    private static void print(String n,String context,String q,String expected,String raw) {
        System.out.println("\n========== TEST "+n+" ==========");
        System.out.println("DONNEES DECLAREES : "+context);
        System.out.println("QUESTION EXACTE : "+q);
        System.out.println("REPONSE BRUTE DU MOTEUR JAVA :");
        System.out.println(raw);
        System.out.println("ATTENDU : "+expected);
        System.out.println("VERDICT : PASS (assertions exécutées)");
    }
    public static void main(String[] args) {
        var incident=new StandaloneTechnicalAssistant();
        tell(incident,"enquete exclusive analyse_http500");
        tell(incident,"hypothese doublon_sql");
        tell(incident,"hypothese panne_mq");
        tell(incident,"hypothese jeton_expire");
        tell(incident,"prevoit doublon_sql trace_sql oui");
        tell(incident,"prevoit panne_mq trace_sql non");
        tell(incident,"prevoit jeton_expire trace_sql oui");
        tell(incident,"prevoit doublon_sql jeton_valide oui");
        tell(incident,"prevoit panne_mq jeton_valide oui");
        tell(incident,"prevoit jeton_expire jeton_valide non");
        tell(incident,"indice trace_sql oui");
        tell(incident,"indice jeton_valide oui");
        String question1="Pourquoi cette conclusion ?";
        var answer1=ask(incident,question1);
        check(answer1.text().startsWith("REPONSE : doublon_sql (déduction conditionnelle)"),"résultat de l'enquête");
        check(answer1.text().contains("INDICES IRREDUCTIBLES : trace_sql=oui"),"premier indice");
        check(answer1.text().contains("jeton_valide=oui"),"deuxième indice");
        check(answer1.text().contains("panne_mq exclue") && answer1.text().contains("jeton_expire exclue"),"rôles explicites");
        check(answer1.text().contains("CONTRE-EXEMPLE : Si l'on retire"),"contre-factuel non inventé");
        check(!answer1.text().contains("causalité vérifiée"),"pas de prétention empirique");
        var before=incident.reply("quelle hypothese reste compatible ?");
        check(before.text().contains("FCI : YES"),"FCI toujours prioritaire");
        print("1 — Diagnostic et explication",
                "Exactement une cause parmi doublon_sql, panne_mq, jeton_expire. "
                +"Prédictions saisies ; observations trace_sql=oui et jeton_valide=oui (non vérifiées).",
                question1,"doublon_sql conditionnel, avec deux indices nécessaires",answer1.text());

        var logic=new StandaloneTechnicalAssistant();
        tell(logic,"enquete exclusive raisonnement_logique");
        tell(logic,"hypothese panne_disque");
        tell(logic,"hypothese panne_reseau");
        tell(logic,"prevoit panne_disque voyant_rouge oui");
        tell(logic,"prevoit panne_reseau voyant_rouge oui");
        tell(logic,"indice voyant_rouge oui");
        String question2="Quel contre-exemple est possible ?";
        var answer2=ask(logic,question2);
        check(answer2.text().startsWith("REPONSE : INDÉTERMINÉ : 2 hypothèse(s) compatibles"),"ambiguïté") ;
        check(answer2.text().contains("Monde compatible 1 : panne_disque") &&
                answer2.text().contains("monde compatible 2 : panne_reseau"),"deux modèles vrais");
        check(answer2.questionStillOpen(),"FCI non clos");
        print("2 — Contre-exemple logique",
                "Deux pannes distinctes prédisent toutes deux voyant_rouge=oui ; le voyant est allumé.",
                question2,"INDÉTERMINÉ ; deux modèles possibles",answer2.text());

        var integrity=new StandaloneTechnicalAssistant();
        tell(integrity,"enquete exclusive donnees_incoherentes");
        tell(integrity,"hypothese cause_a");
        tell(integrity,"hypothese cause_b");
        tell(integrity,"prevoit cause_a service_ok oui");
        tell(integrity,"prevoit cause_b service_ok non");
        tell(integrity,"indice service_ok oui");
        tell(integrity,"indice service_ok non");
        String question3="Quelles prémisses sont contradictoires ?";
        var answer3=ask(integrity,question3);
        check(answer3.text().startsWith("REPONSE : CONTRADICTION : conclusion suspendue"),"inconsistent");
        check(answer3.text().contains("service_ok=oui") && answer3.text().contains("service_ok=non"),"source de conflit");
        check(answer3.text().contains("INDICES IRREDUCTIBLES"),"core contradiction");
        check(answer3.questionStillOpen(),"pas de conclusion dans contradiction");
        print("3 — Détection de contradiction",
                "Les observations déclarées contiennent simultanément service_ok=oui et service_ok=non.",
                question3,"CONTRADICTION, avec les deux affirmations identifiées",answer3.text());

        // Adversarial: removing required evidence reopens the original FCI question.
        tell(incident,"retire indice trace_sql oui");
        var retracted=ask(incident,"Pourquoi cette conclusion ?");
        check(retracted.text().startsWith("REPONSE : INDÉTERMINÉ"),"rétractation réévalue les possibilités");
        check(retracted.text().contains("doublon_sql") && retracted.text().contains("panne_mq"),"alternative revenue");
        check(incident.reply("Peut-on conclure la cause ?").text().contains("FCI : UNKNOWN"),"réouverture FCI");
        tell(integrity,"retire indice service_ok non");
        var repaired=ask(integrity,"Quelles observations sont indispensables ?");
        check(repaired.text().startsWith("REPONSE : cause_a"),"résolution après correction");
        check(!repaired.text().split("INDICES IRREDUCTIBLES : ")[1].split("\n")[0].contains("service_ok=non"),"aucune provenance retirée conservée");

        // Unrelated evidence must not appear in an irreducible explanation.
        var redundancy=new HypothesisReasoningEngine("redundancy",true);
        redundancy.candidate("a"); redundancy.candidate("b");
        redundancy.predicts("a","x",true); redundancy.predicts("b","x",false);
        redundancy.observe("x",true,"observateur_1"); redundancy.observe("irrelevant",true,"observateur_2");
        var audited=redundancy.explain();
        check(audited.conditional(),"raisonnement conditionnel");
        check(audited.irreducibleEvidence().size()==1,"preuve inutile supprimée");
        check(audited.irreducibleEvidence().get(0).label().equals("x=oui"),"preuve utile conservée");
        check(audited.irreducibleEvidence().get(0).source().equals("observateur_1"),"provenance conservée");
        check(audited.counterexample().isPresent(),"alternative issue du modèle");

        // Differential finite enumeration: conditional uniqueness and irreducible core.
        Random random=new Random(0xE514L);
        int cases=60;
        for(int test=0;test<cases;test++) {
            int n=2+random.nextInt(3), signals=1+random.nextInt(3);
            var engine=new HypothesisReasoningEngine("case_"+test,true);
            var prediction=new LinkedHashMap<String,Map<String,Boolean>>();
            for(int h=0;h<n;h++) {
                String name="h"+h; engine.candidate(name);
                var pred=new LinkedHashMap<String,Boolean>();
                for(int s=0;s<signals;s++) {
                    boolean val=random.nextBoolean();
                    engine.predicts(name,"s"+s,val);
                    pred.put("s"+s,val);
                }
                prediction.put(name,pred);
            }
            var inputs=new ArrayList<HypothesisEvidenceAudit.Evidence>();
            for(int s=0;s<signals;s++) {
                if(random.nextBoolean()) {
                    boolean val=random.nextBoolean();
                    engine.observe("s"+s,val,"source_"+s);
                    inputs.add(new HypothesisEvidenceAudit.Evidence("s"+s,val,"source_"+s));
                }
            }
            List<String> expected=new ArrayList<>();
            for(var entry:prediction.entrySet()) {
                boolean consistent=true;
                for(var ev:inputs) if(entry.getValue().get(ev.signal())!=ev.value()) consistent=false;
                if(consistent) expected.add(entry.getKey());
            }
            var result=engine.explain();
            var found=engine.evaluate();
            if(expected.size()==1) {
                check(result.conditional(),"cas "+test+" unique");
                check(result.viableModels().equals(expected),"cas "+test+" gagnant");
                var core=result.irreducibleEvidence();
                check(!core.isEmpty(),"cas "+test+" origine de l'unicité");
                for(int i=0;i<core.size();i++) {
                    List<HypothesisEvidenceAudit.Evidence> minus=new ArrayList<>(core); minus.remove(i);
                    int compatible=0;
                    for(var e:prediction.entrySet()) {
                        boolean valid=true;
                        for(var ev:minus) if(e.getValue().get(ev.signal())!=ev.value()) valid=false;
                        if(valid) compatible++;
                    }
                    check(compatible>1,"cas "+test+" chaque indice du noyau est indispensable");
                }
                check(found.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY,"cas "+test+" corroboration FCI");
            } else if(expected.isEmpty()) {
                check(found.state()==HypothesisReasoningEngine.State.INCONSISTENT,"cas "+test+" aucune affectation");
                check(!result.conditional(),"cas "+test+" explosion refusée");
            } else {
                check(!result.conditional(),"cas "+test+" plusieurs mondes");
                check(found.state()==HypothesisReasoningEngine.State.UNDETERMINED,"cas "+test+" FCI indéterminé");
                check(result.viableModels().equals(expected),"cas "+test+" énumération indépendante");
            }
        }
        System.out.println("\nCONTROLES : "+checks+"/"+checks+" PASS");
        System.out.println("ORACLE DIFFERENTIEL : "+cases+" situations déterministes comparées.");
    }
}
