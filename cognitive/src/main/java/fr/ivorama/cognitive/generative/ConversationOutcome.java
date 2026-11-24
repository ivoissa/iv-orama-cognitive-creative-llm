package fr.ivorama.cognitive.generative;

import java.util.Objects;
import java.util.function.Supplier;

/** Application boundary only. Failures never masquerade as answers or reset provider leases. */
public record ConversationOutcome<T>(Status status, T value) {
    public enum Status { OK, BLOCKED, CONTEXT_LIMIT, BUDGET_LIMIT, INVALID_OUTPUT,
        INVALID_INPUT, UPSTREAM_ERROR, INTERNAL_ERROR }
    public ConversationOutcome {
        Objects.requireNonNull(status);
        if ((status == Status.OK) != (value != null)) throw new IllegalArgumentException("Valeur réservée au succès");
    }
    public static <T> ConversationOutcome<T> execute(Supplier<T> call) {
        Objects.requireNonNull(call);
        try { return new ConversationOutcome<>(Status.OK, Objects.requireNonNull(call.get())); }
        catch (RuntimeException error) { return new ConversationOutcome<>(classify(error), null); }
    }
    public static Status classify(RuntimeException error) {
        if (error instanceof SecurityException) return Status.BLOCKED;
        if (error instanceof GroundedConversation.RejectedOutput || error instanceof EvidenceSynthesis.Rejected)
            return Status.INVALID_OUTPUT;
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.startsWith("CONTEXTE_INSUFFISANT")) return Status.CONTEXT_LIMIT;
        if (message.startsWith("TURN_BUDGET_EXHAUSTED")) return Status.BUDGET_LIMIT;
        if (message.startsWith("Plan d'observation JSON invalide") || message.startsWith("Synthèse de l'agent tronquée")
                || message.startsWith("AGENT_EVIDENCE_REJECTED")) return Status.INVALID_OUTPUT;
        if (error instanceof IllegalArgumentException) return Status.INVALID_INPUT;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        int depth = 0;
        for (Throwable cause = error; cause != null && depth++ < 16 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof java.io.IOException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof InterruptedException) return Status.UPSTREAM_ERROR;
        }
        if (message.startsWith("Échec du moteur local") || message.startsWith("Délai de génération")
                || message.startsWith("Génération interrompue")) return Status.UPSTREAM_ERROR;
        return Status.INTERNAL_ERROR;
    }
    public String explanation() {
        return switch (status) {
            case OK -> "";
            case BLOCKED -> "Réponse bloquée par la politique de contrôle.";
            case CONTEXT_LIMIT -> "Contexte insuffisant pour conserver la demande et les preuves.";
            case BUDGET_LIMIT -> "Budget de génération épuisé.";
            case INVALID_OUTPUT -> "Sortie non conforme ; aucune réponse validée disponible.";
            case INVALID_INPUT -> "Demande ou configuration invalide.";
            case UPSTREAM_ERROR -> "Échec du moteur local ; aucune réponse de remplacement générée.";
            case INTERNAL_ERROR -> "Échec de traitement ; aucune réponse de remplacement générée.";
        };
    }
}
