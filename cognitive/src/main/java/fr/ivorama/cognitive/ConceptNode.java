package fr.ivorama.cognitive;

import java.util.Objects;

/** Nœud ontologique : un individu concret ou une classe de choses. */
public record ConceptNode(String id, String label, Kind kind) {
    public enum Kind { INDIVIDUAL, CLASS }
    public ConceptNode {
        if (id == null || !id.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("ID de concept invalide: " + id);
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Libellé vide");
        label = label.strip();
        Objects.requireNonNull(kind, "kind");
    }
}
