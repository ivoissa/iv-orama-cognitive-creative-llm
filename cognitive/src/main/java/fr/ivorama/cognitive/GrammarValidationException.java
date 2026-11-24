package fr.ivorama.cognitive;

/** Rejet explicite du fragment linguistique supporté ; jamais une panne du solveur. */
public class GrammarValidationException extends IllegalArgumentException {
    public GrammarValidationException(String message) { super(message); }
}
