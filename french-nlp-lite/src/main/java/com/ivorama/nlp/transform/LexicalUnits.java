package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.LexiconMorphologicalEngine;
import com.ivorama.nlp.core.SentenceAnalysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Adverbial multiword lemmas from the bundled French Open Multilingual Wordnet. */
final class LexicalUnits {
    private LexicalUnits() {}

    private static final class Inventory {
        static final Set<String> EXPRESSIONS = load();
        static final int MAX_WORDS = EXPRESSIONS.stream()
                .mapToInt(expression -> expression.split(" ").length).max().orElse(0);
    }

    private static Set<String> load() {
        var expressions = new HashSet<String>();
        try (var input = LexicalUnits.class.getResourceAsStream("/lexicon/omw-fra.tab")) {
            if (input == null) return Set.of();
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var columns = line.split("\t", 3);
                    if (columns.length != 3 || !columns[0].endsWith("-r") || !columns[1].equals("fra:lemma")) continue;
                    String expression = normalize(columns[2]);
                    if (expression.contains(" ")) expressions.add(expression);
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Lecture des locutions OMW impossible", error);
        }
        return Set.copyOf(expressions);
    }

    private static String normalize(String value) {
        return LexiconMorphologicalEngine.norm(value).strip().replaceAll("(?U)\\s+", " ");
    }

    static Set<Integer> protectedIndices(SentenceAnalysis analysis) {
        var indices = new HashSet<Integer>();
        var tokens = analysis.tokens();
        for (int start = 0; start < tokens.size(); start++) {
            var expression = new StringBuilder();
            for (int end = start; end < tokens.size() && end - start < Inventory.MAX_WORDS; end++) {
                var token = tokens.get(end);
                if (token.isPunctuation()) break;
                if (end > start) expression.append(' ');
                expression.append(normalize(token.word()));
                if (end > start && Inventory.EXPRESSIONS.contains(expression.toString())) {
                    for (int i = start; i <= end; i++) indices.add(tokens.get(i).index());
                }
            }
        }
        return indices;
    }
}
