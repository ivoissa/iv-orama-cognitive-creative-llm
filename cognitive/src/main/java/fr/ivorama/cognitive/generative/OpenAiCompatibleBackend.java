package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.generation.TurnBudget;
import fr.ivorama.cognitive.security.LlmSecurityPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Text-only Chat Completions transport, shared by OpenAI and compatible providers. */
final class OpenAiCompatibleBackend implements GenerationBackend {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final GenerationBackend secured;

    OpenAiCompatibleBackend(URI base, String key, String model, Duration timeout, int tokens, boolean json) {
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null || !("https".equals(base.getScheme())
                || "http".equals(base.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost()))) {
            throw new IllegalArgumentException("Endpoint HTTPS ou HTTP sur la boucle locale requis");
        }
        if (key == null || key.isBlank() || model.isBlank() || tokens < 16 || tokens > 8192
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("Clé API ou paramètres du fournisseur invalides");
        }
        URI endpoint = URI.create(base.toString().replaceFirst("/+$", "") + "/chat/completions");
        var policy = LlmSecurityPolicy.fromSystemProperties();
        if (policy.mode() != LlmSecurityPolicy.Mode.ENFORCE) {
            throw new IllegalArgumentException("Le fournisseur OpenAI-compatible requiert security.mode: ENFORCE");
        }
        secured = LlmSecurityGateway.configured().wrap((messages, ignored) ->
                send(endpoint, key, model, timeout, tokens, json, messages));
    }

    @Override public Result generate(List<ChatMessage> messages, Consumer<String> partial) {
        return secured.generate(messages, partial);
    }

    private static Result send(URI endpoint, String key, String model, Duration timeout, int tokens,
                               boolean json, List<ChatMessage> messages) {
        var budget = TurnBudget.current();
        var allocation = budget == null ? new TurnBudget.Allocation(tokens, timeout) : budget.allocate(tokens, timeout);
        Integer outputTokens = null;
        long start = System.nanoTime();
        try {
            var payload = JSON.createObjectNode();
            payload.put("model", model).put("max_completion_tokens", allocation.outputTokens()).put("stream", false);
            if (json) payload.putObject("response_format").put("type", "json_object");
            var entries = payload.putArray("messages");
            for (ChatMessage message : messages) {
                String role;
                String text;
                if (message instanceof SystemMessage m) { role = "system"; text = m.text(); }
                else if (message instanceof UserMessage m) {
                    role = "user";
                    if (m.contents().stream().anyMatch(c -> !(c instanceof TextContent)))
                        throw new IllegalArgumentException("Message utilisateur non textuel");
                    text = m.contents().stream().map(c -> ((TextContent)c).text())
                            .collect(java.util.stream.Collectors.joining("\n"));
                } else if (message instanceof AiMessage m && !m.hasToolExecutionRequests()) {
                    role = "assistant"; text = m.text();
                } else throw new IllegalArgumentException("Type de message non pris en charge");
                if (text != null && text.contains(key)) throw new SecurityException("Clé API dans le contenu");
                entries.addObject().put("role", role).put("content", text);
            }
            var request = HttpRequest.newBuilder(endpoint).timeout(allocation.timeout())
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload))).build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IOException("HTTP fournisseur " + response.statusCode());
                byte[] bytes = body.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) throw new IOException("Réponse fournisseur trop volumineuse");
                var root = JSON.readTree(bytes);
                var choice = root.path("choices").path(0);
                var message = choice.path("message");
                String finish = choice.path("finish_reason").asText("");
                String text = message.path("content").asText("");
                if (message.hasNonNull("tool_calls") || message.hasNonNull("refusal") || text.isBlank()
                        || text.contains(key) || !(finish.equals("stop") || finish.equals("length"))) {
                    throw new IOException("Réponse textuelle fournisseur invalide");
                }
                var usage = root.path("usage");
                outputTokens = usage.path("completion_tokens").isIntegralNumber()
                        ? usage.path("completion_tokens").intValue() : null;
                Integer inputTokens = usage.path("prompt_tokens").isIntegralNumber()
                        ? usage.path("prompt_tokens").intValue() : null;
                return new Result(text, root.path("model").asText(model), finish, System.nanoTime() - start,
                        -1, inputTokens, outputTokens);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Génération interrompue", e);
        } catch (IOException e) {
            throw new IllegalStateException("Échec du fournisseur compatible OpenAI", e);
        } finally {
            if (budget != null) budget.consumed(allocation, outputTokens);
        }
    }
}
