package fr.ivorama.cognitive;

import java.util.Objects;

/** Terme méta : citer une expression n'en garantit ni la preuve ni la vérité. */
public record Quotation(int sourceLevel, Formula formula) {
    public Quotation {
        if (sourceLevel < 0 || sourceLevel == Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid source level");
        Objects.requireNonNull(formula);
    }
    public int targetLevel() { return sourceLevel + 1; }
}
