package com.ivorama.nlp;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import com.ivorama.nlp.transform.SentenceTransformerService;
import com.ivorama.nlp.transform.TransformationConfig;
import com.ivorama.nlp.transform.TransformationConfigLoader;
import com.ivorama.nlp.transform.ValidationMain;

import java.nio.file.Path;
import java.util.Arrays;

public final class MainApp {
    private static final String DEFAULT_SENTENCE = """
        Je partis, en me retournant presque tous les dix pas. Toujours je vis Mareï qui me suivait de l’œil, et chaque fois il me fit un signe de tête amical. J’avoue que j’avais alors un peu honte de ma peur ; toutefois je craignais encore vaguement le loup. Quand j’eus refranchi le ravin, l’épouvante disparut brusquement ; mon chien Voltschok bondit vers moi, venant de je ne sais où, et avec mon chien je me sentais plein de courage.
        """.strip();

    private MainApp() {}

    public static void main(String[] args) {
        if(Runtime.version().feature()!=17)throw new IllegalStateException("IV-ORAMA French NLP requires Java 17 exclusively");
        if (args.length > 0 && "--validate".equals(args[0])) {
            try {
                ValidationMain.main(args);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return;
        }
        if (args.length > 0 && "--analyze".equals(args[0])) {
            String text = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : DEFAULT_SENTENCE;
            try {
                ComplexSentenceAnalyzer.run(text);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Analyse CoreNLP impossible", e);
            }
            return;
        }

        int phraseStart = args.length>0 && "--text".equals(args[0]) ? 1 : 0;
        Path yaml = TransformationConfigLoader.defaultPath();
        if (args.length > 0 && "--config".equals(args[0])) {
            if (args.length < 2) throw new IllegalArgumentException("--config requires a YAML path");
            yaml = Path.of(args[1]);
            phraseStart = 2;
        }
        var settings = TransformationConfigLoader.loadGlobal(yaml, args.length == 0);
        if (args.length == phraseStart && settings.benchmarkEnabled()
                && (args.length == 0 || "--config".equals(args[0]))) {
            try {
                ValidationMain.run(new String[0], settings);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return;
        }
        String sentence = args.length > phraseStart
                ? String.join(" ", Arrays.copyOfRange(args, phraseStart, args.length))
                : java.util.Objects.requireNonNullElse(settings.defaultSentence(), DEFAULT_SENTENCE);
        if (args.length == phraseStart && sentence.isBlank()) throw new IllegalArgumentException("default_sentence doit contenir du texte");

        TransformationConfig config = settings.transformations();
        var lexical = settings.lexical();
        var weights = settings.weights();
        if (lexical.disco().enabled()) {
            System.out.println("LIBERTÉ DISCO ADJECTIFS : " + lexical.disco().semanticFreedom("a"));
            System.out.println("LIBERTÉ DISCO NOMS : " + lexical.disco().semanticFreedom("n"));
        } else {
            System.out.println("TAUX LITE ADJECTIFS : " + config.adjectiveSubstitutionRate() + "%");
            System.out.println("TAUX LITE NOMS : " + config.nounSubstitutionRate() + "%");
        }
        System.out.println("TRANSFORMATIONS : " + config);
        SentenceTransformerService service = new SentenceTransformerService(
                new FrenchNlpAnalyzer(), com.ivorama.nlp.transform.SynonymProviderFactory.create(lexical,weights));
        com.ivorama.nlp.transform.TextTransformationResult textResult = service.transformText(sentence, config);
        System.out.println("TEXTE ORIGINAL : " + textResult.original());
        System.out.println("TEXTE TRANSFORMÉ : " + textResult.transformed());
        try {
            java.nio.file.Files.createDirectories(Path.of("logs"));
            var handler = new java.util.logging.FileHandler("logs/ivorama-nlp-%g.log", 2_000_000, 3, true);
            try {
                handler.setFormatter(new java.util.logging.SimpleFormatter());
                var record = new java.util.logging.LogRecord(java.util.logging.Level.INFO,
                    "CONFIGURATION : " + yaml.toAbsolutePath() + "\nDISCO : " + lexical.disco()
                    + "\nTRANSFORMATIONS : " + config + "\n" + textResult);
                handler.publish(record);
            } finally { handler.close(); }
        } catch (java.io.IOException | SecurityException e) {
            System.err.println("Impossible d'écrire le journal : " + e.getMessage());
        }
    }
}
