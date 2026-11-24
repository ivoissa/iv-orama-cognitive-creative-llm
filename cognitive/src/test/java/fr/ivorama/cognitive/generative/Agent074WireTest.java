package fr.ivorama.cognitive.generative;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import fr.ivorama.cognitive.generation.TurnTelemetry;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK requests to a deterministic HTTP fixture; these are NOT real Qwen answers. */
class Agent074WireTest {
    @TempDir Path root;
    @Test void nestedProposerAndObserverShareTheSameNativeBudget() throws Exception {
        String path = "src/main/java/demo/Foo.java";
        Files.createDirectories(root.resolve(path).getParent());
        Files.writeString(root.resolve(path), "package demo; class Foo { int number() { return 1; } }\n");
        var replies = new ArrayDeque<>(List.of(Agent074Test.action("READ", path, "", 1, 1),
                Agent074Test.action("FINISH", "", "", 1, 1),
                "{\"rationale\":\"Exemple de transport\",\"edits\":[{\"path\":\""+path+"\",\"oldText\":\"return 1;\",\"newText\":\"return 2;\"}]}"));
        var received = new CopyOnWriteArrayList<JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            received.add(mapper.readTree(exchange.getRequestBody()));
            String response = mapper.writeValueAsString(Map.of("model", "agent-fixture:tag", "message",
                    Map.of("role", "assistant", "content", replies.removeFirst()), "done", false)) + "\n" +
                    "{\"model\":\"agent-fixture:tag\",\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":20}\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        String previous = System.getProperty("ivorama.agent.outputBudget");
        try {
            System.setProperty("ivorama.agent.outputBudget", "64");
            var config = new LocalLlmConfig(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
                    "agent-fixture:tag", 4096, 128, 2, 4, Duration.ofSeconds(10), 0);
            try (var telemetry = TurnTelemetry.start()) {
                var agent = new QwenPatchProposalAgent(root, new OllamaGenerationBackend(config, true), new OllamaGenerationBackend(config, true));
                assertTrue(agent.propose("Changer le nombre pour cet essai de transport").syntaxValid());
                assertEquals(3, telemetry.snapshot().calls().size());
                assertEquals(60, telemetry.snapshot().knownOutputTokens());
            }
            assertEquals(List.of(64, 44, 24), received.stream().map(n -> n.path("options").path("num_predict").asInt()).toList());
            assertNull(fr.ivorama.cognitive.generation.TurnBudget.current());
        } finally {
            if (previous == null) System.clearProperty("ivorama.agent.outputBudget"); else System.setProperty("ivorama.agent.outputBudget", previous);
            server.stop(0);
        }
    }
}
