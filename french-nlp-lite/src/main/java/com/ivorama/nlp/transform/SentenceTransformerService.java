package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import com.ivorama.nlp.core.SentenceAnalysis;

public final class SentenceTransformerService {
    private final FrenchNlpAnalyzer analyzer;
    private final SentenceTransformerEngine engine;

    public SentenceTransformerService(FrenchNlpAnalyzer analyzer, SynonymProvider synonymProvider) {
        this.analyzer = java.util.Objects.requireNonNull(analyzer, "analyzer");
        this.engine = new SentenceTransformerEngine(synonymProvider);
    }

    /** Transforme toutes les phrases analysées, dans leur ordre initial. */
    public TextTransformationResult transformText(String text, TransformationConfig config) {
        return transformAnalyses(text, analyzer.analyze(text), config, engine);
    }

    /** Point d'entrée testable sans modèle CoreNLP ; aucun résultat de phrase codé en dur. */
    static TextTransformationResult transformAnalyses(String text,
            java.util.List<SentenceAnalysis> analyses, TransformationConfig config,
            SentenceTransformerEngine engine) {
        java.util.Objects.requireNonNull(text, "text");
        java.util.Objects.requireNonNull(analyses, "analyses");
        if (analyses.isEmpty()) {
            return new TextTransformationResult(text, text, java.util.List.of());
        }
        engine.prepareDocument(analyses);
        boolean allowGender=analyses.size()==1||analyses.stream().flatMap(a->a.tokens().stream()).noneMatch(t->t.pos().equals("PRON"));
        var budget = new SentenceTransformerEngine.EmphasisBudget(config.emphasisFallbackLimit());
        java.util.List<TransformationResult> results = analyses.stream()
                .map(sentence -> engine.transform(sentence, config, allowGender, budget)).toList();
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        boolean offsetsAvailable = analyses.stream().allMatch(a -> a.beginOffset() >= 0);
        boolean anyOffsets = analyses.stream().anyMatch(a -> a.beginOffset() >= 0);
        if (anyOffsets && !offsetsAvailable)
            throw new IllegalArgumentException("Mélange d'analyses avec et sans offsets");
        if (offsetsAvailable) {
            for (int i = 0; i < analyses.size(); i++) {
                SentenceAnalysis a = analyses.get(i);
                if (a.beginOffset() < cursor || a.endOffset() > text.length()
                        || !text.substring(a.beginOffset(), a.endOffset()).equals(a.originalText()))
                    throw new IllegalArgumentException("Offsets CoreNLP incohérents");
                out.append(text, cursor, a.beginOffset());
                out.append(results.get(i).transformed());
                cursor = a.endOffset();
            }
            out.append(text.substring(cursor));
        } else {
            // Les analyses synthétiques sans offsets gardent le comportement historique.
            out.append(results.stream().map(TransformationResult::transformed)
                    .collect(java.util.stream.Collectors.joining(" ")));
        }
        return new TextTransformationResult(text, out.toString(), results);
    }

    public TransformationResult transform(String sentence, TransformationConfig config) {
        SentenceAnalysis analysis = analyzer.analyzeFirstSentence(sentence);
        engine.prepareDocument(java.util.List.of(analysis));
        return engine.transform(analysis, config);
    }
}
