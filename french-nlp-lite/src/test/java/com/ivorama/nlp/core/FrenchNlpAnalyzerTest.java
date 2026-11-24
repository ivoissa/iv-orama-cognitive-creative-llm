package com.ivorama.nlp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Test d'intégration réel CoreNLP, exécuté par mvn test lorsque les dépendances sont disponibles. */
class FrenchNlpAnalyzerTest {
    @Test
    void extractsMainSubjectAndDirectObject() {
        SentenceAnalysis a = new FrenchNlpAnalyzer().analyzeFirstSentence("Le chat mange sa pâtée dans le jardin.");
        assertEquals("mange", a.token(a.rootTokenIndex()).word());
        assertNotNull(a.subject());
        assertTrue(a.subject().tokenIndices().contains(2));
        assertTrue(a.objects().stream().anyMatch(o -> o.role() == ConstituentRole.DIRECT_OBJECT));
        assertFalse(a.circumstantials().isEmpty());
    }
}
