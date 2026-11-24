package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.generation.TurnBudget;
import java.time.Duration;
import java.util.Properties;

/** Nested observer/proposer calls share the same native output/time allocation. */
final class AgentBudget implements AutoCloseable {
    record Settings(int outputTokens, Duration timeout) {
        Settings {
            if (outputTokens < 16 || outputTokens > 8192)
                throw new IllegalArgumentException("ivorama.agent.outputBudget doit être compris entre16 et8192");
            if (timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofMinutes(30)) > 0)
                throw new IllegalArgumentException("ivorama.agent.timeout doit être compris entre1 et1800 secondes");
        }
        static Settings from(Properties properties) {
            long output = number(properties, "ivorama.agent.outputBudget", 3072, 16, 8192);
            long seconds = number(properties, "ivorama.agent.timeout", 240, 1, 1800);
            return new Settings((int) output, Duration.ofSeconds(seconds));
        }
        private static long number(Properties p, String key, long fallback, long min, long max) {
            String raw = p.getProperty(key);
            if (raw == null) return fallback;
            try {
                long value = Long.parseLong(raw);
                if (value < min || value > max) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                // Deliberately omit the supplied value; do not silently override an explicit limit.
                throw new IllegalArgumentException(key + " doit être un entier entre" + min + " et" + max);
            }
        }
    }
    private final TurnBudget owned;
    private AgentBudget(Settings settings) {
        owned = TurnBudget.current() == null ? new TurnBudget(settings.outputTokens(), settings.timeout()) : null;
    }
    static AgentBudget open() {
        if (TurnBudget.current() != null) return new AgentBudget(null);
        Properties snapshot = new Properties();
        snapshot.putAll(System.getProperties());
        return new AgentBudget(Settings.from(snapshot));
    }
    static AgentBudget open(Settings settings) {
        return new AgentBudget(java.util.Objects.requireNonNull(settings));
    }
    @Override public void close() { if (owned != null) owned.close(); }
}
