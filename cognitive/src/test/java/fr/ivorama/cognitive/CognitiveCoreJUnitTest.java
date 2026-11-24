package fr.ivorama.cognitive;

import org.junit.jupiter.api.Test;

class CognitiveCoreJUnitTest {
    @Test void verificationDesPreuves() { SelfTest.proofTests(); }
    @Test void semantiqueEtIndetermination() { SelfTest.semanticTests(); }
    @Test void memoireEtRevision() { SelfTest.memoryTests(); }
    @Test void repriseDeterminativeOrientee() { SelfTest.rdoTests(); }
    @Test void cycleInterrogatifIntegre() { SelfTest.integratedCycleTests(); }
    @Test void revueSupportsEtPartitions() { SelfTest.review043Tests(); }
    @Test void observationsTraceables() { SelfTest.observation043Tests(); }
    @Test void remplacementsAtomiques() { SelfTest.replacement043Tests(); }
    @Test void lexiqueSemantique() { SemanticLexiconTest.runAll(); }
    @Test void francaisControle() { FrenchControlledTest.runAll(); }
    @Test void soixanteScenariosInterrogatifs() { SelfTest.scenarioMatrixTests(); }
    @Test void revueLinguistique056() { ReviewLing003FixTest.runAll(); }
    @Test void explicationEtInvestigation() { ExplanationActTest.runAll(); }
    @Test void preuveSemantiqueTwoSat() { TwoSatDifferentialTest.runAll(); }
}
