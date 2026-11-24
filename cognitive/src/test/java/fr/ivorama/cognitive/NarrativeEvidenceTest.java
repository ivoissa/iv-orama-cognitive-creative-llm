package fr.ivorama.cognitive;

import java.util.*;

/** Reproducible questions directly submitted to the compiled offline assistant. */
public final class NarrativeEvidenceTest {
    private static int assertions;
    private static void ok(boolean test,String why) { assertions++; if(!test) throw new AssertionError(why); }
    private static void command(StandaloneTechnicalAssistant app,String line) {
        var r=app.reply(line);
        ok(!r.kind().endsWith("ERROR") && !r.kind().equals("HYPOTHESIS_UNKNOWN"),"Commande rejetée: "+line+" => "+r.text());
    }
    private static StandaloneTechnicalAssistant.Response question(StandaloneTechnicalAssistant app,String line) {
        var r=app.reply(line);
        System.out.println("QUESTION EXACTE : "+line);
        System.out.println("RÉPONSE BRUTE DU MOTEUR :");
        System.out.println(r.text());
        System.out.println("FIN DE RÉPONSE\n");
        return r;
    }
    public static void main(String[] args) {
        System.out.println("=== IV-ORAMA 0.6.15 : TROIS QUESTIONS RÉELLES ===");
        var incident=new StandaloneTechnicalAssistant();
        command(incident,"enquete exclusive incident_api");
        for(String h:List.of("doublon_sql","mq_timeout","jwt_expire")) command(incident,"hypothese "+h);
        command(incident,"prevoit doublon_sql contrainte_unique oui");
        command(incident,"prevoit doublon_sql mq_ok oui");
        command(incident,"prevoit mq_timeout contrainte_unique oui");
        command(incident,"prevoit mq_timeout mq_ok non");
        command(incident,"prevoit jwt_expire contrainte_unique non");
        command(incident,"prevoit jwt_expire mq_ok oui");
        command(incident,"expression contrainte_unique | Les logs affichent une violation d'unicité | Les logs attestent l'absence de violation d'unicité");
        command(incident,"expression mq_ok | Le service MQ répond normalement | Le service MQ est indisponible");
        var intake=incident.reply("rapport log_01 : Les logs affichent une violation d'unicité. Le service MQ répond normalement. La documentation cite le mot incident.");
        ok(intake.kind().equals("REPORT_INGESTED"),"rapport reconnu");
        ok(intake.text().contains("2 observation(s) enregistrée(s), 1 proposition(s) non interprétée(s)"),"partie non interprétée explicitée");
        ok(intake.text().contains("NON INTERPRÉTÉ : La documentation cite le mot incident"),"pas d'hallucination sur la documentation");
        System.out.println("--- TEST 1 : INCIDENT API ---");
        System.out.println("DONNÉES : "+intake.text());
        var one=question(incident,"Pourquoi cette conclusion ?");
        ok(one.text().startsWith("REPONSE : doublon_sql"),"cause conditionnelle attendue");
        ok(one.text().contains("contrainte_unique=oui") && one.text().contains("mq_ok=oui"),"les deux preuves sont nécessaires");
        ok(one.questionStillOpen()==false,"état par type de réponse de l'assistant");
        // Test 2: two models remain compatible even if one signal is observed.
        var maths=new StandaloneTechnicalAssistant();
        command(maths,"enquete exclusive calcul");
        command(maths,"hypothese methode_a");
        command(maths,"hypothese methode_b");
        command(maths,"prevoit methode_a resultat7 oui");
        command(maths,"prevoit methode_b resultat7 oui");
        command(maths,"expression resultat7 | Le calcul donne sept | Le calcul ne donne pas sept");
        var undecided=maths.reply("rapport calcul_01 : Le calcul donne sept. Le calcul donne peut-être huit.");
        ok(undecided.text().contains("1 observation(s) enregistrée(s), 1 proposition(s) non interprétée(s)"),"modalité refusée");
        System.out.println("--- TEST 2 : RAISONNEMENT MATHÉMATIQUE ---");
        System.out.println("DONNÉES : "+undecided.text());
        var two=question(maths,"Quel contre-exemple est possible ?");
        ok(two.text().startsWith("REPONSE : INDÉTERMINÉ : 2 hypothèse(s) compatibles"),"non identification causale");
        ok(two.text().contains("methode_a") && two.text().contains("methode_b"),"deux mondes compatibles");
        // Test 3: contradictory independent reports; source retraction reopens the FCI obligation.
        var deploy=new StandaloneTechnicalAssistant();
        command(deploy,"enquete exclusive deploiement");
        command(deploy,"hypothese service_ok");
        command(deploy,"hypothese service_ko");
        command(deploy,"prevoit service_ok endpoint_vivant oui");
        command(deploy,"prevoit service_ko endpoint_vivant non");
        command(deploy,"expression endpoint_vivant | Le endpoint répond à la sonde | Le endpoint échoue à la sonde");
        var first=deploy.reply("rapport controle_a : Le endpoint répond à la sonde.");
        var second=deploy.reply("rapport controle_b : Le endpoint échoue à la sonde.");
        ok(first.kind().equals("REPORT_INGESTED") && second.kind().equals("REPORT_INGESTED"),"deux rapports divergents");
        System.out.println("--- TEST 3 : CONTRADICTION ENTRE RAPPORTS ---");
        System.out.println("DONNÉES : "+first.text()+"\n"+second.text());
        var three=question(deploy,"Quelles prémisses sont contradictoires ?");
        ok(three.text().startsWith("REPONSE : CONTRADICTION : conclusion suspendue"),"contradiction identifiée");
        ok(three.text().contains("endpoint_vivant=oui") && three.text().contains("endpoint_vivant=non"),"preuves incompatibles");
        command(deploy,"retire rapport controle_b");
        ok(deploy.reply("Peut-on conclure la cause ?").text().startsWith("REPONSE : service_ok"),"après retrait, conclusion rétablie");
        command(deploy,"retire rapport controle_a");
        var reopened=deploy.reply("Peut-on conclure la cause ?");
        ok(reopened.text().startsWith("REPONSE : INDÉTERMINÉ"),"retrait intégral réouvre FCI");
        ok(reopened.text().contains("réouverture : true"),"FCI réouvert selon le moteur");
        // Adversarial suite: exact clauses, modality, positive/negative and atomicity.
        var direct=new HypothesisReasoningEngine("test",true);
        direct.candidate("a"); direct.candidate("b");
        direct.predicts("a","flag",true);direct.predicts("b","flag",false);
        var layer=new NarrativeEvidenceLayer(direct);
        layer.register("flag","le voyant est allumé","le voyant est éteint");
        ok(layer.vocabularySize()==1,"lexique enregistré");
        var rejected=List.of("La notice cite : le voyant est allumé", "Peut-être le voyant est allumé",
                "le voyant est allumé dans un document", "le voyant est allumé sauf exception",
                "Le voyant est allumé ?", "\"le voyant est allumé\"", "Le voyant est allumé probablement");
        for(int i=0;i<rejected.size();i++) {
            var x=layer.ingest("exclu"+i,rejected.get(i));
            ok(x.claims().isEmpty(),"exclusion ambiguïté "+i);
            ok(direct.observations().isEmpty(),"aucun fait créé sur ambiguïté "+i);
        }
        var accepted=layer.ingest("src1"," LE VOYANT EST ALLUMÉ. ");
        ok(accepted.claims().size()==1,"insensibilité casse et accents");
        ok(direct.evaluate().state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY,"conclusion supportée");
        try {layer.ingest("src2","le voyant est allumé"); throw new AssertionError("duplication non refusée");}
        catch(IllegalArgumentException good) {ok(good.getMessage().contains("déjà active"),"source distincte ne double pas l'observation");}
        ok(layer.reportCount()==1,"échec atomique ne crée pas un rapport");
        try {layer.register("another","le voyant est allumé","autre"); throw new AssertionError("collision lexicale");}
        catch(IllegalArgumentException good) {ok(true,"collision sans écrasement");}
        layer.withdraw("src1");
        ok(direct.evaluate().state()==HypothesisReasoningEngine.State.UNDETERMINED,"rétractation FCI");
        // Reproducible equivalence of punctuation/case variants for 100 clauses.
        Random rng=new Random(0xE515);
        for(int i=0;i<100;i++) {
            String word=rng.nextBoolean()?"le voyant est allumé":"le voyant est éteint";
            String altered=rng.nextBoolean()?word.toUpperCase(Locale.ROOT):word;
            String suffix=rng.nextBoolean()?".":";";
            var x=layer.ingest("serie"+i,altered+suffix);
            ok(x.claims().size()==1,"lexicalisation variante "+i);
            ok(x.claims().get(0).value()==word.contains("allumé"),"polarité variante "+i);
            layer.withdraw("serie"+i);
            ok(direct.observations().isEmpty(),"retrait exact "+i);
        }
        System.out.println("CONTROLES NOUVEAU MODULE : "+assertions+" / "+assertions+" PASS");
        System.out.println("PROPRIÉTÉS : 100 variantes déterministes de phrases, 7 ambiguïtés refusées, rétractions et non-invention.");
    }
}
