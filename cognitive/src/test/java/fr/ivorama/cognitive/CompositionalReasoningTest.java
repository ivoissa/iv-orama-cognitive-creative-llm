package fr.ivorama.cognitive;

import java.util.*;

/** Tests with three actual questions plus negative tests and independent finite oracle. */
public final class CompositionalReasoningTest {
    private static int checks;
    private static void ok(boolean condition,String label) {
        checks++; if(!condition) throw new AssertionError(label);
    }
    private static void show(int number,String type,String data,String question,String raw) {
        System.out.println("===== TEST "+number+" — "+type+" =====");
        System.out.println("DONNÉES EXACTES : "+data);
        System.out.println("QUESTION EXACTE : "+question);
        System.out.println("RÉPONSE BRUTE DU MOTEUR :\n"+raw);
        System.out.println("===== FIN TEST "+number+" =====\n");
    }
    private static void reject(Runnable op,String label) {
        try {op.run();throw new AssertionError("Devait rejeter : "+label);}
        catch(IllegalArgumentException | IllegalStateException valid) {ok(true,label);}
    }
    private static CompositionalReasoningEngine.Literal lit(String name,boolean positive) {
        return new CompositionalReasoningEngine.Literal(name,positive);
    }
    public static void main(String[] args) {
        System.out.println("IV-ORAMA Cognitive 0.6.16 — TROIS QUESTIONS RÉELLEMENT EXÉCUTÉES");
        // 1. A three-step technical inference, including a conjunctive rule.
        var incident=new CompositionalFrenchDialogue();
        incident.expression("sql23505","le journal contient sqlstate 23505","le journal ne contient pas sqlstate 23505");
        incident.expression("unicite","une contrainte unique est violée","aucune contrainte unique n'est violée");
        incident.expression("ecriture","l'écriture en base est refusée","l'écriture en base est acceptée");
        incident.expression("api500","l'api retourne une erreur 500","l'api ne retourne pas une erreur 500");
        incident.expression("operation","l'opération d'écriture est déclenchée","l'opération d'écriture n'est pas déclenchée");
        incident.rule("r_sql","si le journal contient sqlstate 23505, alors une contrainte unique est violée");
        incident.rule("r_write","si une contrainte unique est violée et l'opération d'écriture est déclenchée, alors l'écriture en base est refusée");
        incident.rule("r_http","si l'écriture en base est refusée, alors l'api retourne une erreur 500");
        var in=incident.report("log_a","Le journal contient SQLSTATE 23505. L'opération d'écriture est déclenchée. La doc mentionne SQLSTATE 23505.");
        ok(in.accepted()==2 && in.ignored().size()==1,"2 faits seulement");
        String q1="Est-ce que l'api retourne une erreur 500 ?";
        String a1=incident.question(q1);
        show(1,"CHAÎNE CAUSALE CONDITIONNELLE","3 règles déclarées ; rapport log_a",q1,a1);
        ok(a1.contains("REPONSE : OUI"),"test 1 conclusion oui");
        ok(a1.contains("r_sql")&&a1.contains("r_write")&&a1.contains("r_http"),"test 1 chaîne complète");
        ok(a1.contains("log_a_0")&&a1.contains("log_a_1"),"test 1 deux faits nécessaires");
        ok(a1.contains("SOURCES INDISPENSABLES")&&a1.contains("r_write"),"test 1 sources critiques");
        incident.withdrawReport("log_a");
        String afterWithdraw=incident.question(q1);
        ok(afterWithdraw.contains("REPONSE : INDÉTERMINÉ"),"test 1 retrait = inconnu");
        ok(afterWithdraw.contains("QUESTION ROUVERTE : true"),"test 1 FCI réouvert");
        // 2. Conjunction: an active JPA transaction is insufficient without valid flush.
        var jpa=new CompositionalFrenchDialogue();
        jpa.expression("transaction","la transaction est ouverte","la transaction est fermée");
        jpa.expression("flush","le flush a réussi","le flush a échoué");
        jpa.expression("commit","le commit est autorisé","le commit est refusé");
        jpa.rule("r_commit","si la transaction est ouverte et le flush a réussi, alors le commit est autorisé");
        var r1=jpa.report("jpa_1","La transaction est ouverte.");
        ok(r1.accepted()==1,"transaction observée");
        String q2="Est-ce que le commit est autorisé ?";
        String a2=jpa.question(q2);
        show(2,"CONJONCTION / DONNÉE MANQUANTE","règle transaction ET flush ; seule transaction connue",q2,a2);
        ok(a2.contains("REPONSE : INDÉTERMINÉ"),"test 2 inconnue");
        ok(a2.contains("flush=oui"),"test 2 préalable manquant");
        jpa.report("jpa_2","Le flush a réussi.");
        ok(jpa.question(q2).contains("REPONSE : OUI"),"test 2 devient démontrable");
        jpa.withdrawReport("jpa_2");
        ok(jpa.question(q2).contains("REPONSE : INDÉTERMINÉ"),"test 2 correction inverse");
        // 3. Contradiction from two reports must block conclusion, even unrelated one.
        var deploy=new CompositionalFrenchDialogue();
        deploy.expression("endpoint","le endpoint répond","le endpoint ne répond pas");
        deploy.expression("publication","la publication est validée","la publication n'est pas validée");
        deploy.rule("r_pub","si le endpoint répond, alors la publication est validée");
        deploy.report("sonde_a","Le endpoint répond.");
        deploy.report("sonde_b","Le endpoint ne répond pas.");
        String q3="Est-ce que la publication est validée ?";
        String a3=deploy.question(q3);
        show(3,"CONTRADICTION / SUSPENSION","rapports sonde_a et sonde_b contradictoires",q3,a3);
        ok(a3.contains("REPONSE : CONTRADICTION"),"test 3 contradiction");
        ok(a3.contains("endpoint=oui")&&a3.contains("endpoint=non"),"test 3 paire en conflit");
        deploy.withdrawReport("sonde_b");
        ok(deploy.question(q3).contains("REPONSE : OUI"),"test 3 retrait lève incohérence");
        deploy.withdrawReport("sonde_a");
        ok(deploy.question(q3).contains("REPONSE : INDÉTERMINÉ"),"test 3 aucune preuve résiduelle");
        // Adversarial checks. Unknown text cannot create a fact.
        var adversarial=new CompositionalFrenchDialogue();
        adversarial.expression("disponible","le service est disponible","le service est indisponible");
        var ignored=adversarial.report("ambigu","Peut-être le service est disponible. La documentation dit que le service est disponible. \"Le service est disponible\".");
        ok(ignored.accepted()==0&&ignored.ignored().size()==3,"refus modal, documentation, citation");
        ok(adversarial.engine().factCount()==0,"aucun fait depuis discours incertain");
        ok(adversarial.question("Est-ce que le service est disponible ?").contains("REPONSE : INDÉTERMINÉ"),"pas de fait inventé");
        ok(adversarial.question("Est-ce que le processeur est en panne ?").contains("NON INTERPRÉTÉ"),"question inconnue refusée");
        reject(()->adversarial.rule("mal","si le service est disponible, alors le stockage est défectueux"),"règle à conclusion inconnue rejetée");
        ok(adversarial.engine().ruleCount()==0,"aucune règle partielle");
        var cycle=new CompositionalReasoningEngine();
        cycle.rule("ca",List.of(lit("x",true)),lit("y",true),"hypothèse cyclique");
        cycle.rule("cb",List.of(lit("y",true)),lit("x",true),"hypothèse cyclique");
        ok(cycle.ask(lit("x",true)).state()==CompositionalReasoningEngine.State.UNKNOWN,"cycle sans graine = inconnu");
        cycle.fact("graine",lit("x",true),"déclaration explicite");
        ok(cycle.revisit(lit("x",true)).state()==CompositionalReasoningEngine.State.YES,"cycle avec graine = vrai");
        ok(cycle.ask(lit("y",true)).state()==CompositionalReasoningEngine.State.YES,"cycle propagation valide");
        cycle.withdraw("graine");
        ok(cycle.ask(lit("y",true)).state()==CompositionalReasoningEngine.State.UNKNOWN,"retrait graine éteint le cycle");
        // Negative literal is a real negation, not absence of proof.
        var negative=new CompositionalReasoningEngine();
        negative.rule("neg_rule",List.of(lit("disque",false)),lit("sauvegarde",false),"règle négative déclarée");
        ok(negative.ask(lit("sauvegarde",true)).state()==CompositionalReasoningEngine.State.UNKNOWN,"absence de fait négatif != NON");
        negative.fact("neg_log",lit("disque",false),"sonde rapportée");
        ok(negative.revisit(lit("sauvegarde",true)).state()==CompositionalReasoningEngine.State.NO,"preuve explicite de négation");
        // Independent routes: do NOT call a source indispensable when another
        // complete proof survives its removal.
        var routes=new CompositionalReasoningEngine();
        routes.fact("source_a",lit("signal_a",true),"rapport A");
        routes.fact("source_b",lit("signal_b",true),"rapport B");
        routes.rule("route_a",List.of(lit("signal_a",true)),lit("resultat",true),"voie A");
        routes.rule("route_b",List.of(lit("signal_b",true)),lit("resultat",true),"voie B");
        var routeResult=routes.ask(lit("resultat",true));
        ok(routeResult.state()==CompositionalReasoningEngine.State.YES,"deux supports alternatifs");
        ok(routeResult.indispensableSources().isEmpty(),"aucun appui de la première preuve n'est indispensable");
        routes.withdraw("route_a");
        ok(routes.revisit(lit("resultat",true)).state()==CompositionalReasoningEngine.State.YES,"seconde voie demeure");
        routes.withdraw("route_b");
        ok(routes.revisit(lit("resultat",true)).state()==CompositionalReasoningEngine.State.UNKNOWN,"deux voies retirées : inconnu");
        // Explicit contrapositive with a real negative observation, not closed-world absence.
        var backward=new CompositionalReasoningEngine();
        backward.rule("cause_effet",List.of(lit("cause",true)),lit("effet",true),"condition déclarée");
        backward.fact("pas_effet",lit("effet",false),"sonde déclarée");
        var backwards=backward.ask(lit("cause",true));
        ok(backwards.state()==CompositionalReasoningEngine.State.NO,"modus tollens explicite");
        ok(backwards.proofSteps().stream().anyMatch(x->x.contains("CONTRAPOSITION")),"preuve par contraposition visible");
        // Classical combination not found through ordinary forward-chaining.
        var proofByCases=new CompositionalReasoningEngine();
        proofByCases.rule("yes",List.of(lit("a",true)),lit("b",true),"alternative logique 1");
        proofByCases.rule("no",List.of(lit("a",true)),lit("b",false),"alternative logique 2");
        var nonA=proofByCases.ask(lit("a",true));
        ok(nonA.state()==CompositionalReasoningEngine.State.NO,"combinaison des clauses implique non-a");
        ok(nonA.proofSteps().stream().anyMatch(x->x.contains("MODÈLES")),"limite explicative déclarée");
        // Independent truth-table oracle: enumerate models satisfying rules and observations.
        // Difference from forward-chaining code, tests 40 deterministic acyclic cases.
        Random random=new Random(0x160016);
        int oracles=0;
        for(int round=0;round<40;round++) {
            var probe=new CompositionalReasoningEngine();
            List<boolean[]> facts=new ArrayList<>();
            List<int[]> ruleBody=new ArrayList<>();
            List<Integer> ruleHead=new ArrayList<>();
            for(int i=0;i<4;i++) if(random.nextBoolean()) {
                boolean positive=random.nextBoolean();
                probe.fact("f"+i,lit("v"+i,positive),"oracle déterministe");
                facts.add(new boolean[]{i==0,i==1,i==2,i==3,positive});
            }
            for(int i=1;i<4;i++) if(random.nextBoolean()) {
                int parent=random.nextInt(i);
                probe.rule("rr"+i,List.of(lit("v"+parent,true)),lit("v"+i,true),"règle de test");
                ruleBody.add(new int[]{parent});ruleHead.add(i);
            }
            for(int q=0;q<4;q++) {
                // Enumerate all 16 candidate valuations. Check entailment by SAT semantics.
                boolean hasTrue=false,hasFalse=false;
                for(int mask=0;mask<16;mask++) {
                    boolean sat=true;
                    for(boolean[] f:facts) {
                        int idx=f[0]?0:f[1]?1:f[2]?2:3;
                        if(((mask&(1<<idx))!=0)!=f[4]) {sat=false;break;}
                    }
                    if(!sat)continue;
                    for(int k=0;k<ruleBody.size();k++) {
                        int parent=ruleBody.get(k)[0],head=ruleHead.get(k);
                        if((mask&(1<<parent))!=0&&(mask&(1<<head))==0){sat=false;break;}
                    }
                    if(!sat)continue;
                    if((mask&(1<<q))!=0)hasTrue=true;else hasFalse=true;
                }
                var expected=!hasTrue&&!hasFalse?CompositionalReasoningEngine.State.INCONSISTENT:
                        hasTrue&&hasFalse?CompositionalReasoningEngine.State.UNKNOWN:
                        hasTrue?CompositionalReasoningEngine.State.YES:CompositionalReasoningEngine.State.NO;
                var actual=probe.ask(lit("v"+q,true)).state();
                ok(expected==actual,"oracle indépendant ronde "+round+", q="+q+" attendu="+expected+" réel="+actual);
                oracles++;
            }
        }
        System.out.println("CONTRÔLES CIBLÉS : "+checks+" / "+checks+" PASS");
        System.out.println("ORACLE INDÉPENDANT (tables de vérité finies) : "+oracles+" / "+oracles+" concordances");
        System.out.println("Limites : lexique explicitement déclaré, aucune vérité empirique certifiée, univers propositionnel fini.");
    }
}
