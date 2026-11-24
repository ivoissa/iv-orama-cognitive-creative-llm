package fr.ivorama.cognitive.generation;

import org.junit.jupiter.api.Test;

class Generation072Test {
    @Test void contract(){Generation072Cases.sourceGrounding();}
    @Test void replacements(){Generation072Cases.uniqueRepairs();}
    @Test void invalidReplacements(){Generation072Cases.maliciousRepairs();}
    @Test void eligibility(){Generation072Cases.repairEligibility();}
    @Test void telemetry(){Generation072Cases.telemetry();}
    @Test void isolation()throws Exception{Generation072Cases.threadIsolation();}
}
