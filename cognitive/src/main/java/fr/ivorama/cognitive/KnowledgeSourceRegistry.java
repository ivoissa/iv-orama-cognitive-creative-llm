package fr.ivorama.cognitive;

import java.util.*;

/** Registre de provenance : une déclaration n'est pas une vérification indépendante. */
public final class KnowledgeSourceRegistry {
    public enum Status { UNVERIFIED, HUMAN_CHECKED, REVOKED }
    public record Source(String id, String description, String reference, Status status) {
        public Source {
            if (id == null || !id.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("ID de source invalide: " + id);
            if (description == null || description.isBlank())
                throw new IllegalArgumentException("Description de source obligatoire");
            reference = reference == null ? "" : reference.strip();
            Objects.requireNonNull(status);
        }
    }
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private long version;
    public synchronized void add(Source source) {
        Objects.requireNonNull(source);
        if (sources.putIfAbsent(source.id(), source) != null)
            throw new IllegalArgumentException("Source déjà connue: " + source.id());
        version++;
    }
    public synchronized void setStatus(String id, Status status) {
        Source old = required(id);
        // Réactiver une source révoquée nécessite une nouvelle source identifiée.
        if (old.status() == Status.REVOKED && status != Status.REVOKED)
            throw new IllegalArgumentException("Source révoquée: enregistrer une nouvelle source");
        if (old.status() != status) {
            sources.put(id, new Source(id, old.description(), old.reference(), status));
            version++;
        }
    }
    public synchronized Source required(String id) {
        Source s = sources.get(id);
        if (s == null) throw new IllegalArgumentException("Source inconnue: " + id);
        return s;
    }
    public synchronized boolean usable(String id) { return required(id).status() != Status.REVOKED; }
    public synchronized Collection<Source> all() { return List.copyOf(sources.values()); }
    public synchronized long version() { return version; }
}
