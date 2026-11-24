package fr.ivorama.cognitive.render;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import com.ivorama.nlp.transform.SentenceTransformerService;
import com.ivorama.nlp.transform.SynonymProviderFactory;
import com.ivorama.nlp.transform.TransformationConfig;
import com.ivorama.nlp.transform.TransformationConfigLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** Local, opt-in rendering of an already approved plain-text passage. */
public final class LiteTextRenderer {
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
    private final SentenceTransformerService service;
    private final TransformationConfig config;

    public LiteTextRenderer(Path yaml) {
        var settings = TransformationConfigLoader.loadGlobal(Objects.requireNonNull(yaml), false);
        if (settings.lexical().disco().enabled() || settings.lexical().libraryEnabled()) {
            throw new IllegalArgumentException("Le rendu local exige un profil Lite.");
        }
        service = new SentenceTransformerService(new FrenchNlpAnalyzer(),
                SynonymProviderFactory.create(settings.lexical(), settings.weights()));
        config = settings.transformations();
    }

    public String renderPlainText(String approvedText) {
        return service.transformText(Objects.requireNonNull(approvedText), config).transformed();
    }
    /** Console presentation only. Keeps the original line endings and highlights changed lines. */
    public String renderForConsole(String approvedText) {
        var breaks = LINE_BREAK.matcher(Objects.requireNonNull(approvedText));
        var output = new StringBuilder();
        String fence = "";
        int start = 0;
        while (true) {
            boolean hasBreak = breaks.find();
            int end = hasBreak ? breaks.start() : approvedText.length();
            String line = approvedText.substring(start, end);
            var marker = FENCE.matcher(line);
            if (marker.matches()) {
                String delimiter = marker.group(1);
                if (fence.isEmpty()) {
                    fence = delimiter;
                } else if (delimiter.charAt(0) == fence.charAt(0)
                        && delimiter.length() >= fence.length() && marker.group(2).isBlank()) {
                    fence = "";
                }
                output.append(line);
            } else if (!fence.isEmpty() || line.codePoints().noneMatch(Character::isLetter)) {
                output.append(line);
            } else {
                var result = service.transformText(line, config);
                output.append(result.transformed());
                if (!line.equals(result.transformed())) {
                    var rules = result.sentences().stream()
                            .flatMap(sentence -> sentence.appliedRules().stream()).distinct().toList();
                    output.append("  **[Lite");
                    if (!rules.isEmpty()) output.append(" / ").append(String.join(", ", rules));
                    output.append("]**");
                }
            }
            if (!hasBreak) return output.toString();
            output.append(breaks.group());
            start = breaks.end();
        }
    }
}
