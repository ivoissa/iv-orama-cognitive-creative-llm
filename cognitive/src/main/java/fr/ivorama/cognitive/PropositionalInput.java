package fr.ivorama.cognitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Analyseur d'un micro-langage formel ; pas de compréhension libre du français. */
public final class PropositionalInput {
    private final List<String> words;
    private int pos;

    private PropositionalInput(String expression) {
        Objects.requireNonNull(expression, "expression");
        words = tokenize(expression);
        if (words.isEmpty()) throw new IllegalArgumentException("Expression vide");
    }

    public static Formula parse(String expression) {
        PropositionalInput parser = new PropositionalInput(expression);
        Formula result = parser.implication();
        if (parser.pos != parser.words.size())
            throw new IllegalArgumentException("Terme inattendu : " + parser.words.get(parser.pos));
        return result;
    }

    private static List<String> tokenize(String text) {
        ArrayList<String> output = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) { i++; continue; }
            if (text.startsWith("->", i)) { output.add("->"); i += 2; continue; }
            char c = text.charAt(i);
            if (c == '(' || c == ')' || c == '&' || c == '!') {
                output.add(String.valueOf(c)); i++; continue;
            }
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z') {
                int begin = i++;
                while (i < text.length()) {
                    char a = text.charAt(i);
                    if ((a >= 'A' && a <= 'Z') || (a >= 'a' && a <= 'z')
                            || (a >= '0' && a <= '9') || a == '_') i++;
                    else break;
                }
                output.add(text.substring(begin, i)); continue;
            }
            throw new IllegalArgumentException("Caractère non pris en charge : " + c);
        }
        return List.copyOf(output);
    }

    private boolean next(String expected) {
        if (pos < words.size() && words.get(pos).equals(expected)) {pos++; return true;}
        return false;
    }

    private Formula implication() {
        Formula left = conjunction();
        return next("->") ? Formula.implies(left, implication()) : left;
    }

    private Formula conjunction() {
        Formula left = unary();
        while (next("&") || next("et")) left = Formula.and(left, unary());
        return left;
    }

    private Formula unary() {
        if (next("!") || next("non")) return Formula.not(unary());
        if (next("(")) {
            Formula result = implication();
            if (!next(")")) throw new IllegalArgumentException("Parenthèse fermante attendue");
            return result;
        }
        if (pos >= words.size()) throw new IllegalArgumentException("Terme manquant");
        String token = words.get(pos++);
        if (token.equals("->") || token.equals(")") || token.equals("&") || token.equals("et"))
            throw new IllegalArgumentException("Atome attendu, reçu : " + token);
        return Formula.atom(token);
    }
}
