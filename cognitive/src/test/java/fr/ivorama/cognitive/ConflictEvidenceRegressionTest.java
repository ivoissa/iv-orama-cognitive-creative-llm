package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

class ConflictEvidenceRegressionTest {
    @Test void conflictShowsExactSourcesAndExcludesUnrelatedPremises() {
        var entity = new SemanticIntermediateRepresentation.EntityRef("item", "a");
        var p = new Atom("p", List.of(new Constant(entity)));
        var q = new Atom("q", List.of(new Constant(entity)));
        var problem = new Problem("Conflit déclaré", List.of(entity),
                List.of(new Predicate("p", List.of("item")), new Predicate("q", List.of("item"))),
                List.of(new Premise("positive", p, new Evidence(Origin.DECLARATION, "source-A", "Affirmation positive")),
                        new Premise("negative", new Not(p), new Evidence(Origin.DECLARATION, "source-B", "Négation explicite")),
                        new Premise("unrelated", q, new Evidence(Origin.DECLARATION, "source-C", "PASSAGE HORS CONFLIT"))),
                q, List.of(), List.of());
        var answer = new StructuredReasoningService(new IntegratedQuestionCycle())
                .ask(new SemanticIntermediateRepresentation.LogicProblemFrame(problem));
        assertEquals(StructuredReasoningService.Status.INCONSISTENT, answer.status());
        assertEquals(Set.of("positive", "negative"), Set.copyOf(answer.conflictPremises()));
        assertTrue(answer.checkedProof().isEmpty());
        assertTrue(answer.explanation().contains("source-A\" | extrait=\"Affirmation positive"));
        assertTrue(answer.explanation().contains("source-B\" | extrait=\"Négation explicite"));
        assertFalse(answer.explanation().contains("PASSAGE HORS CONFLIT"));
        assertTrue(answer.explanation().contains("pas nécessairement de cardinalité minimale"));
    }

    @Test void sourceControlCharactersAreEscapedAndOrdinaryAnswersStayUnchanged() {
        var translator = new FrenchStructuredTranslator();
        String source = "Le capteur c1 est actif. Le capteur c1 n'est pas actif. Le capteur c1 est-il actif ?";
        var base = translator.translate(source).problem().orElseThrow();
        var premises = base.premises().stream().map(p -> new Premise(p.id(), p.expression(),
                new Evidence(Origin.HYPOTHESIS, "ligne\nsource\u001b", "texte\r\n\"cité\"\u001b"))).toList();
        var problem = new Problem(source, base.entities(), base.predicates(), premises, base.goal(), List.of(), List.of());
        var service = new StructuredReasoningService(new IntegratedQuestionCycle());
        var answer = service.ask(new SemanticIntermediateRepresentation.LogicProblemFrame(problem));
        assertTrue(answer.explanation().contains("source=\"ligne\\nsource\\u001b\""));
        assertTrue(answer.explanation().contains("extrait=\"texte\\r\\n\\\"cité\\\"\\u001b\""));
        assertFalse(answer.explanation().contains("\u001b"));
        var ordinary = service.askJson(translator.translate("Le capteur c1 est actif. Le capteur c1 est-il actif ?").json());
        assertEquals(StructuredReasoningService.Status.TRUE_CONDITIONALLY, ordinary.status());
        assertFalse(ordinary.explanation().contains("Sources déclarées du conflit"));
    }
}
