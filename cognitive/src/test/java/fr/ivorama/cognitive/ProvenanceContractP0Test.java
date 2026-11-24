package fr.ivorama.cognitive;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
/** Maven CI gate: real production core + integrated cycle, no Ollama/Qwen. */
public final class ProvenanceContractP0Test {
  @Test void certificateContractsCannotBeForged(){assertEquals(21,ProvenanceContractP0Probe.runAll());}
  @Test void fullInterrogativeCycleRemainsConsistent(){assertEquals(12,P0WholeProjectSmoke.runAll());}
}
