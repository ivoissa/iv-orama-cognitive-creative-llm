package fr.ivorama.cognitive;

import java.util.Objects;

/** Une prémisse signée et traçable, jamais un fait empirique automatiquement certifié. */
public record RelationAssertion(String id, String subjectId, Predicate predicate,
                                String objectId, boolean positive, String sourceId) {
    public enum Predicate { INSTANCE_OF, SUBCLASS_OF }
    public RelationAssertion {
        valid(id, "id"); valid(subjectId, "subjectId"); valid(objectId, "objectId");
        valid(sourceId, "sourceId"); Objects.requireNonNull(predicate);
    }
    private static void valid(String value, String name) {
        if (value == null || !value.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Identifiant invalide " + name + ": " + value);
    }
}
