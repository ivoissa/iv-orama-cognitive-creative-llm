package fr.ivorama.cognitive.agent;

import org.junit.jupiter.api.Test;

/** Adversarial cases (secret oracle, compound key names, syntax-check fallback) wired into Maven/Surefire. */
public class ObserverHardeningTest {
    @Test void searchIsNotAnOracleForSecrets() throws Exception {
        ObserverHardeningCases.caseSearchIsNotAnOracle();
    }
    @Test void compoundSensitiveKeyNamesAreRedacted() throws Exception {
        ObserverHardeningCases.caseSensitiveKeyVariants();
    }
    @Test void syntaxCheckDegradesCleanly() throws Exception {
        ObserverHardeningCases.caseSyntaxCheckDegradesCleanly();
    }
}
