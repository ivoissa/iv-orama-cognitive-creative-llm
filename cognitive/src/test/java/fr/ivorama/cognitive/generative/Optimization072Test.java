package fr.ivorama.cognitive.generative;
import org.junit.jupiter.api.Test;
class Optimization072Test {
    @Test void comparison(){Optimization072Cases.controlledComparison();}
    @Test void invalidRepair(){Optimization072Cases.rejectedRepair();}
    @Test void routing(){Optimization072Cases.adaptivePlanning();}
    @Test void analysis(){Optimization072Cases.sourceAnalysis();}
    @Test void regeneration(){Optimization072Cases.fullRegeneration();}
    @Test void failure(){Optimization072Cases.failedTransport();}
}
