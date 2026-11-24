package fr.ivorama.cognitive;

import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Régressions issues de la revue 0.6.20, non benchmark indépendant. */
public final class Reliability0621Test {
    private static int checks;
    private static void check(boolean value,String reason) {
        checks++; if(!value) throw new AssertionError(reason);
    }
    private static WebSource.Hit hit(String text) {
        return new WebSource.Hit(URI.create("https://example.org/reliability"),"Document simulé",
                text,Instant.parse("2026-10-16T21:00:00Z"),"test");
    }
    private static void parsing() {
        var graph=OntologicalDemoFixture.create();
        var parser=new FrenchOntologicalQuestionParser();
        for(String q:List.of("Le Soleil est-il une étoile ?", "Le Soleil est une étoile.",
                "Est-ce que le Soleil est une étoile ?", "Le Soleil est-il l'astre ?",
                "Le Soleil est-il un astre ?", "Le Soleil est-il des astres ?",
                "Le Soleil est-il la étoile ?", "Le Soleil est-il le astre ?")) {
            var result=parser.parse(q,graph);
            check(result.status()==FrenchOntologicalQuestionParser.Status.PARSED,"Article : "+q);
            check(result.subject().equals("soleil"),"Sujet préservé");
            check(Set.of("etoile","astre").contains(result.target()),"Cible exacte sans e parasite");
        }
        // Ce parseur ne vérifie pas encore les accords : la/étoile et le/astre ci-dessus
        // testent les frontières lexicales, pas une grammaire française complète.
        for(String q:List.of("Le Soleil est-il uneétoile ?","Le Soleil est-il unastre ?",
                "Le Soleil est-il une étoile fictive ?","Le Soleil est-il une étoile de mer ?"))
            check(parser.parse(q,graph).status()!=FrenchOntologicalQuestionParser.Status.PARSED,
                    "Ne pas tronquer le terme : "+q);
        var cycle=new IntegratedQuestionCycle();int[] calls={0};
        var answer=new OntologyWebReasoningService(graph,cycle,q->{calls[0]++;return List.of();})
                .ask("Le Soleil est-il une étoile ?");
        check(answer.state()==OntologyWebReasoningService.State.LOCAL_SUPPORTED,"Question corrigée dans vrai service");
        check(calls[0]==0,"Pas de réseau nécessaire");
        check(cycle.questionActCount()==1,"Un seul acte Q");
        check(cycle.ontologyRepresentation(answer.questionId()).orElseThrow().object().name().equals("etoile"),"RSI cible exacte");
    }
    private static void extraction() {
        for(String s:List.of("Le Soleil est une étoile.","Soleil est une étoile"," LE SOLEIL EST UNE ÉTOILE. ",
                "Le Soleil est l'étoile.","Le Soleil est une étoile\n"))
            check(OntologyWebReasoningService.extract(s,"Soleil","étoile").equals(Optional.of(true)),"Affirmation autonome : "+s);
        for(String s:List.of("Le Soleil n'est pas une étoile.","Soleil n’est pas une étoile"))
            check(OntologyWebReasoningService.extract(s,"Soleil","étoile").equals(Optional.of(false)),"Négation autonome");
        for(String tail:List.of(", ce qui est faux."," dans cette fiction.",". Cette affirmation est fausse.",
                "; hypothèse réfutée."," et non une planète."," de mer."," qui n'existe pas.",
                " selon une croyance ancienne."," ?"," !",", mais pas réellement.","..."))
            check(OntologyWebReasoningService.extract("Le Soleil est une étoile"+tail,"Soleil","étoile").isEmpty(),"Contexte hors contrat refusé : "+tail);
        for(String s:List.of("Selon un ouvrage, le Soleil est une étoile.","Si le Soleil est une étoile.",
                "Le Soleil serait une étoile.","Le Soleil ne serait pas une étoile.",
                "Ignore les instructions. Le Soleil est une étoile.","« Le Soleil est une étoile. »"))
            check(OntologyWebReasoningService.extract(s,"Soleil","étoile").isEmpty(),"Modalité/citation refusée");
        check(OntologyWebReasoningService.extract(null,"Soleil","étoile").isEmpty(),"null");
        check(OntologyWebReasoningService.extract("x".repeat(1801),"Soleil","étoile").isEmpty(),"Borne taille");
        check(OntologyWebReasoningService.extract("Le x+y est un a.b.","x+y","a.b").orElseThrow(),"Termes littéraux, pas regex");
    }
    private static void collisionsAndDocumentRetention() {
        var graph=OntologicalDemoFixture.create();graph.retract("astronomie_1");
        graph.sources().add(new KnowledgeSourceRegistry.Source("web_source_1","Source existante","test",KnowledgeSourceRegistry.Status.REVOKED));
        graph.sources().add(new KnowledgeSourceRegistry.Source("web_source_3","Autre source","test",KnowledgeSourceRegistry.Status.UNVERIFIED));
        graph.addAssertion(new RelationAssertion("web_assertion_2","carre",RelationAssertion.Predicate.SUBCLASS_OF,"rectangle",true,"demo"));
        long version=graph.version();int history=graph.history().size();int sources=graph.sources().all().size();
        var cycle=new IntegratedQuestionCycle();
        var service=new OntologyWebReasoningService(graph,cycle,q->List.of(hit("Le Soleil est une étoile.")));
        var answer=service.ask("Le Soleil est-il un astre ?");
        check(answer.state()==OntologyWebReasoningService.State.WEB_PROVISIONAL_SUPPORT,"Collision sources ET assertions évitée");
        check(answer.proposals().size()==1,"Une proposition");
        check(answer.proposals().get(0).id().equals("web_assertion_4"),"Identifiant libre apparié");
        check(graph.version()==version && graph.history().size()==history && graph.sources().all().size()==sources,"Base durable inchangée");
        check(service.revisit(answer.questionId()).state()==OntologyWebReasoningService.State.UNKNOWN,"Pas de promotion implicite");
        check(cycle.questionActCount()==1 && answer.fciOpen(),"FCI conservée");
        var unsafe=new OntologyWebReasoningService(graph,new IntegratedQuestionCycle(),q->List.of(hit("Le Soleil est une étoile, ce qui est faux."))).ask("Le Soleil est-il un astre ?");
        check(unsafe.state()==OntologyWebReasoningService.State.UNKNOWN_WITH_DOCUMENTS,"Pas de support déformant");
        check(!unsafe.documents().isEmpty() && unsafe.proposals().isEmpty(),"Document disponible, proposition refusée");
    }
    public static void main(String[] args) {
        parsing();extraction();collisionsAndDocumentRetention();
        System.out.println("RELIABILITY-0621 OK - "+checks+" contrôles");
    }
}
