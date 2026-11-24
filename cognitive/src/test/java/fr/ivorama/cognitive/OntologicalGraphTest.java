package fr.ivorama.cognitive;

import java.nio.file.*;
import java.util.*;

/** Tests indépendants et reproductibles, exécutables sans JUnit. */
public final class OntologicalGraphTest {
    private static int checks;
    private static void ok(boolean result, String label) {
        checks++;
        if (!result) throw new AssertionError("ÉCHEC " + checks + " : " + label);
    }
    private static void rejected(Runnable action, String label) {
        boolean thrown = false;
        try { action.run(); } catch (IllegalArgumentException e) { thrown = true; }
        ok(thrown,label);
    }
    private static OntologicalInferenceEngine.Evaluation ask(OntologicalKnowledgeGraph g,
            String a, RelationAssertion.Predicate type, String b) {
        return new OntologicalInferenceEngine().evaluate(g,a,type,b);
    }
    private static final RelationAssertion.Predicate INSTANCE = RelationAssertion.Predicate.INSTANCE_OF;
    private static final RelationAssertion.Predicate SUBCLASS = RelationAssertion.Predicate.SUBCLASS_OF;
    private static void add(OntologicalKnowledgeGraph g, String id, String a,
                            RelationAssertion.Predicate p, String b, boolean positive, String src) {
        g.addAssertion(new RelationAssertion(id,a,p,b,positive,src));
    }
    private static void source(OntologicalKnowledgeGraph g, String id) {
        g.sources().add(new KnowledgeSourceRegistry.Source(id,"Déclaration de test", "test://"+id,
                KnowledgeSourceRegistry.Status.UNVERIFIED));
    }
    private static void individual(OntologicalKnowledgeGraph g,String id) {
        g.addNode(new ConceptNode(id,id,ConceptNode.Kind.INDIVIDUAL));
    }
    private static void kind(OntologicalKnowledgeGraph g,String id) {
        g.addNode(new ConceptNode(id,id,ConceptNode.Kind.CLASS));
    }
    private static void astronomy() {
        OntologicalKnowledgeGraph g = OntologicalDemoFixture.create();
        var e = ask(g,"soleil",INSTANCE,"astre");
        ok(e.verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,"astronomie chaîne");
        ok(e.support().orElseThrow().assertionIds().equals(List.of("astronomie_1","astronomie_2")), "2 pas exacts");
        ok(e.support().orElseThrow().verify(g),"preuve validée indépendamment");
        ok(!e.allSourcesHumanChecked(),"démo jamais certifiée empiriquement");
        ok(new OntologicalDialogue(g).ask("Le Soleil est-il un astre ?").startsWith("OUI"),"français inversion");
        ok(new OntologicalDialogue(g).ask("Est-ce que le Soleil est un astre ?").startsWith("OUI"),"français est-ce que");
        ok(new OntologicalDialogue(g).ask("Une étoile est-elle un astre ?").startsWith("OUI"),"question sur sous-classe");
        ok(new OntologicalDialogue(g).ask("La Lune est-elle un astre ?").startsWith("[UNKNOWN_TERM]"),"aucune invention");
        OntologicalProofTrace proof = e.support().orElseThrow();
        OntologicalProofTrace forged = new OntologicalProofTrace("soleil",INSTANCE,"astre",true,
                List.of(proof.steps().get(1),proof.steps().get(0)));
        ok(!forged.verify(g),"ordre de preuve falsifié refusé");
        g.retract("astronomie_2");
        ok(!proof.verify(g),"ancienne preuve invalide après retrait");
        ok(ask(g,"soleil",INSTANCE,"astre").verdict() == OntologicalInferenceEngine.Verdict.UNKNOWN,
                "pas de preuve post retrait");
        add(g,"new_edge","etoile",SUBCLASS,"astre",true,"demo");
        ok(ask(g,"soleil",INSTANCE,"astre").verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,
                "alternative rétablissant la preuve");
        g.sources().setStatus("demo",KnowledgeSourceRegistry.Status.REVOKED);
        ok(ask(g,"soleil",INSTANCE,"astre").verdict() == OntologicalInferenceEngine.Verdict.UNKNOWN,
                "révocation de provenance propage la perte de soutien");
        rejected(() -> g.sources().setStatus("demo",KnowledgeSourceRegistry.Status.HUMAN_CHECKED),
                "source révoquée non réactivable silencieusement");
    }
    private static void mathematics() {
        OntologicalKnowledgeGraph g = OntologicalDemoFixture.create();
        ok(ask(g,"figure_a",INSTANCE,"rectangle").verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,
                "figure carrée => rectangle");
        ok(ask(g,"rectangle",SUBCLASS,"carre").verdict() == OntologicalInferenceEngine.Verdict.UNKNOWN,
                "interdit inversion illégitime");
        ok(new OntologicalDialogue(g).ask("Un rectangle est-il un carré ?").startsWith("INDÉTERMINÉ"),
                "monde ouvert ≠ faux");
        ok(ask(g,"rectangle",SUBCLASS,"rectangle").verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,
                "réflexivité axiomatique");
        add(g,"neg_math","rectangle",SUBCLASS,"carre",false,"demo");
        ok(ask(g,"rectangle",SUBCLASS,"carre").verdict() == OntologicalInferenceEngine.Verdict.REFUTED,
                "négation explicite non déduite de l'absence");
        add(g,"wrong_math","rectangle",SUBCLASS,"carre",true,"demo");
        var both = ask(g,"rectangle",SUBCLASS,"carre");
        ok(both.verdict() == OntologicalInferenceEngine.Verdict.CONTRADICTORY,"soutien et négation présents");
        ok(both.support().isPresent() && both.refutation().isPresent(),"deux preuves indépendantes");
        rejected(()->add(g,"typeerr","figure_a",SUBCLASS,"carre",true,"demo"),
                "relation mal typée refusée");
        rejected(()->add(g,"ghost","figure_a",INSTANCE,"inconnu",true,"demo"),
                "référent inexistant refusé");
    }
    private static void languageAndSoftware() {
        OntologicalKnowledgeGraph g = OntologicalDemoFixture.create();
        String answer = new OntologicalDialogue(g).ask("Mercure est-il un métal ?");
        ok(answer.startsWith("[AMBIGUOUS]"),"Mercure ambigu = clarification");
        ok(answer.contains("mercure_planete") && answer.contains("mercure_element"),
                "les deux sens explicités");
        ok(ask(g,"incident42",INSTANCE,"erreur_http").verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,
                "classification informatique sans causalité");
        ok(new OntologicalDialogue(g).ask("Un HTTP 500 est-il toujours provoqué par SQL ?")
                .startsWith("[OUT_OF_GRAMMAR]"),"pas d.invention de causalité à partir de 500");
        // Ajouts non contenus dans le corpus DEMO : le raisonneur utilise les métadonnées, pas le mot Soleil.
        source(g,"expert_medical");
        individual(g,"nova"); kind(g,"lapin"); kind(g,"mammifere");
        add(g,"bio_1","nova",INSTANCE,"lapin",true,"expert_medical");
        add(g,"bio_2","lapin",SUBCLASS,"mammifere",true,"expert_medical");
        ok(new OntologicalDialogue(g).ask("Nova est-elle un mammifère ?").startsWith("OUI"),
                "transfert de domaine vers biologie sans cas codé");
        var proof = ask(g,"nova",INSTANCE,"mammifere").support().orElseThrow();
        ok(proof.verify(g),"preuve pour nouveau domaine");
        g.sources().setStatus("expert_medical", KnowledgeSourceRegistry.Status.HUMAN_CHECKED);
        ok(ask(g,"nova",INSTANCE,"mammifere").allSourcesHumanChecked(),
                "marquage humain explicite, non automatisé");
        g.sources().setStatus("expert_medical", KnowledgeSourceRegistry.Status.REVOKED);
        ok(!proof.verify(g),"preuve invalidée par révocation source");
        ok(ask(g,"nova",INSTANCE,"mammifere").verdict() == OntologicalInferenceEngine.Verdict.UNKNOWN,
                "aucun soutien après révocation");
    }
    private static void storageAndCycles() throws Exception {
        OntologicalKnowledgeGraph g = OntologicalDemoFixture.create();
        source(g,"usertest");
        add(g,"relation_neg","soleil",INSTANCE,"astre",false,"usertest");
        g.retract("relation_neg");
        Path file = Files.createTempFile("ivorama-0619-", ".tsv");
        try {
            OntologicalGraphStore.save(g,file);
            OntologicalKnowledgeGraph copy = OntologicalGraphStore.load(file);
            ok(copy.nodes().size() == g.nodes().size(),"persistance nœuds");
            ok(copy.history().size() == g.history().size(),"persistance assertions retirées");
            ok(copy.candidates("mercure").size() == 2,"persistance de la polysémie");
            ok(ask(copy,"soleil",INSTANCE,"astre").verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED,
                    "même sémantique après rechargement");
            ok(copy.history().stream().anyMatch(x->x.assertion().id().equals("relation_neg") && !x.active()),
                    "marque de retrait persistée");
            copy.sources().setStatus("demo",KnowledgeSourceRegistry.Status.REVOKED);
            OntologicalGraphStore.save(copy,file);
            ok(ask(OntologicalGraphStore.load(file),"soleil",INSTANCE,"astre").verdict() == OntologicalInferenceEngine.Verdict.UNKNOWN,
                    "révocation persistée");
            Files.writeString(file,"IVOKG\t999\n");
            boolean failed=false;
            try { OntologicalGraphStore.load(file); } catch (java.io.IOException expected) { failed=true; }
            ok(failed,"version de format incompatible refusée");
        } finally { Files.deleteIfExists(file); }
        OntologicalKnowledgeGraph cycles = new OntologicalKnowledgeGraph();
        source(cycles,"s"); kind(cycles,"a"); kind(cycles,"b"); kind(cycles,"c");
        add(cycles,"ab","a",SUBCLASS,"b",true,"s");
        add(cycles,"bc","b",SUBCLASS,"c",true,"s");
        add(cycles,"ca","c",SUBCLASS,"a",true,"s");
        ok(ask(cycles,"a",SUBCLASS,"c").support().orElseThrow().steps().size()==2,
                "cycle fini, plus court chemin");
    }
    private static void randomizedAgainstIndependentOracle() {
        Random rnd = new Random(619);
        for (int trial=0;trial<150;trial++) {
            OntologicalKnowledgeGraph g = new OntologicalKnowledgeGraph(); source(g,"s");
            int n=2 + rnd.nextInt(9);
            for (int i=0;i<n;i++) kind(g,"c"+i);
            boolean[][] edges = new boolean[n][n];
            for (int i=0;i<n;i++) for (int j=0;j<n;j++) if (i!=j && rnd.nextInt(5)==0) {
                edges[i][j] = true;
                add(g,"r"+i+"_"+j,"c"+i,SUBCLASS,"c"+j,true,"s");
            }
            boolean[][] reachable = new boolean[n][n];
            for (int i=0;i<n;i++) {
                System.arraycopy(edges[i],0,reachable[i],0,n);
                reachable[i][i]=true;
            }
            for (int k=0;k<n;k++) for (int i=0;i<n;i++) for (int j=0;j<n;j++)
                reachable[i][j] |= reachable[i][k] && reachable[k][j];
            for (int i=0;i<n;i++) for (int j=0;j<n;j++) {
                var ev = ask(g,"c"+i,SUBCLASS,"c"+j);
                boolean actual = ev.verdict() == OntologicalInferenceEngine.Verdict.SUPPORTED;
                ok(actual==reachable[i][j],"oracle indépendant Warshall essai "+trial+"/"+i+"/"+j);
                if (actual) ok(ev.support().orElseThrow().verify(g),"certificat graphe aléatoire "+trial);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        astronomy(); mathematics(); languageAndSoftware(); storageAndCycles();
        randomizedAgainstIndependentOracle();
        System.out.println("IVOC 0.6.19 : "+checks+" assertions réussies (3 domaines + résilience + oracle aléatoire)");
    }
}
