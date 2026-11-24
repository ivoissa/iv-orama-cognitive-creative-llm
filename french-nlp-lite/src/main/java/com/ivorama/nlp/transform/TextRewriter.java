package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.SentenceAnalysis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TextRewriter {
    private final List<SentenceRule> rules;

    public TextRewriter(List<SentenceRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public String rewrite(SentenceAnalysis sentence) {
        var result = new StringBuilder(sentence.originalText());
        for (var edit : plan(sentence)) result.replace(edit.beginOffset(), edit.endOffset(), edit.renderedReplacement());
        return result.toString();
    }

    /** Éditions retenues dans leur ordre d’application. */
    public List<TextEdit> plan(SentenceAnalysis sentence) {
        String text = sentence.originalText();
        var plans = rules.stream().map(rule -> List.copyOf(rule.edits(sentence)))
                .filter(plan -> !plan.isEmpty())
                .sorted(Comparator.comparingInt(TextRewriter::priority).reversed()).toList();
        var accepted = new ArrayList<TextEdit>();
        for (var plan : plans) {
            var checked = new ArrayList<TextEdit>();
            for (var edit : plan) {
                if (edit.endOffset() > text.length()) {
                    throw new IllegalArgumentException("Édition hors de la phrase originale");
                }
                if (checked.stream().anyMatch(edit::overlaps)) {
                    throw new IllegalArgumentException("Éditions incompatibles au sein d’une même règle");
                }
                checked.add(edit);
            }
            if (plan.stream().noneMatch(edit -> accepted.stream().anyMatch(edit::overlaps))) accepted.addAll(plan);
        }
        accepted.sort(Comparator.comparingInt(TextEdit::beginOffset)
                .thenComparingInt(TextEdit::endOffset).reversed());
        return List.copyOf(accepted);
    }

    private static int priority(List<TextEdit> plan) {
        return plan.stream().mapToInt(TextEdit::priority).min().orElseThrow();
    }
}
