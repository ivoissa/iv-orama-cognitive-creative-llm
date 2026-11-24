package com.ivorama.nlp.transform;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression: a determiner must not veto an otherwise curated noun candidate. */
class LexicalSafetyDeterminerTest {
    @Test
    void nounWithDeterminerCanBeReplaced() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.yodaSimple(),
                        new TransformationConfig(true, true, true, false, false, false, false, false, 20261107L));
        assertEquals("Le félin donne la pâtée au canidé.", result.transformed());
        assertEquals(2, result.replacements().size());
    }

    @Test
    void nonCuratedCandidateIsStillRejectedByLexicalSafety() {
        var analysis = TestFixtures.yodaSimple();
        var token = analysis.tokens().get(1);
        var provider = new ResourceSynonymProvider();
        assertFalse(LexicalSafety.isSafeReplacement(analysis, token, "inconnu", provider, SynonymRegister.COURANT));
    }
}
