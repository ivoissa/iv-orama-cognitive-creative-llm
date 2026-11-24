package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Maven full-project gate; no LLM/provider connection required. */
final class P03BudgetAndIntegrationJUnitTest {
    @Test void budgetsAndFciProvenanceRemainSound() {
        assertEquals(39, P03BudgetAndIntegrationTestMain.runAll());
    }
}
