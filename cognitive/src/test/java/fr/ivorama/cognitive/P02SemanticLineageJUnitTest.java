package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project Maven gate; no LLM/network calls. */
class P02SemanticLineageJUnitTest {
    @Test void semanticProvenanceAndRetractions() {
        assertTrue(P02SemanticLineageTestMain.runAll()>=30);
    }
}
