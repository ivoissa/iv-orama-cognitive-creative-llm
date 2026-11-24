package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.SentenceAnalysis;
import java.util.List;

@FunctionalInterface
public interface SentenceRule {
    /** Les éditions retournées forment une transaction indivisible. */
    List<TextEdit> edits(SentenceAnalysis sentence);
}
