package fr.ivorama.cognitive;

/** Petit corpus d'exemples DÉCLARÉS, non une base de connaissances universelle. */
public final class OntologicalDemoFixture {
    private OntologicalDemoFixture() {}
    public static OntologicalKnowledgeGraph create() {
        OntologicalKnowledgeGraph graph = new OntologicalKnowledgeGraph();
        graph.sources().add(new KnowledgeSourceRegistry.Source("demo",
                "Prémisses de démonstration (non contrôlées de manière indépendante)",
                "fixture 0.6.19", KnowledgeSourceRegistry.Status.UNVERIFIED));
        node(graph,"soleil","Soleil",ConceptNode.Kind.INDIVIDUAL);
        node(graph,"etoile","étoile",ConceptNode.Kind.CLASS);
        node(graph,"astre","astre",ConceptNode.Kind.CLASS);
        node(graph,"carre","carré",ConceptNode.Kind.CLASS);
        node(graph,"rectangle","rectangle",ConceptNode.Kind.CLASS);
        node(graph,"figure_a","figure A",ConceptNode.Kind.INDIVIDUAL);
        node(graph,"mercure_planete","Mercure (planète)",ConceptNode.Kind.INDIVIDUAL);
        node(graph,"mercure_element","mercure (élément chimique)",ConceptNode.Kind.INDIVIDUAL);
        node(graph,"planete","planète",ConceptNode.Kind.CLASS);
        node(graph,"metal","métal",ConceptNode.Kind.CLASS);
        node(graph,"incident42","incident 42",ConceptNode.Kind.INDIVIDUAL);
        node(graph,"erreur_http","erreur HTTP",ConceptNode.Kind.CLASS);
        graph.alias("étoiles","etoile"); graph.alias("astres","astre");
        graph.alias("rectangles","rectangle"); graph.alias("carrés","carre");
        graph.alias("mercure","mercure_planete"); graph.alias("mercure","mercure_element");
        assertion(graph,"astronomie_1","soleil",RelationAssertion.Predicate.INSTANCE_OF,"etoile");
        assertion(graph,"astronomie_2","etoile",RelationAssertion.Predicate.SUBCLASS_OF,"astre");
        assertion(graph,"math_1","carre",RelationAssertion.Predicate.SUBCLASS_OF,"rectangle");
        assertion(graph,"math_2","figure_a",RelationAssertion.Predicate.INSTANCE_OF,"carre");
        assertion(graph,"chimie_1","mercure_element",RelationAssertion.Predicate.INSTANCE_OF,"metal");
        assertion(graph,"planetes_1","mercure_planete",RelationAssertion.Predicate.INSTANCE_OF,"planete");
        assertion(graph,"info_1","incident42",RelationAssertion.Predicate.INSTANCE_OF,"erreur_http");
        return graph;
    }
    private static void node(OntologicalKnowledgeGraph g, String id,String label,ConceptNode.Kind kind) {
        g.addNode(new ConceptNode(id,label,kind));
    }
    private static void assertion(OntologicalKnowledgeGraph g, String id, String subject,
                                  RelationAssertion.Predicate predicate, String target) {
        g.addAssertion(new RelationAssertion(id,subject,predicate,target,true,"demo"));
    }
}
