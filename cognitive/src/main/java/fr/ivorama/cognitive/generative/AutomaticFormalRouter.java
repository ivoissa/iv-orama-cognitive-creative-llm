package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.*;
import java.util.*;
import java.util.regex.Pattern;

/** Recognition only: no FCI, solver, LLM or Web call during routing. */
public final class AutomaticFormalRouter {
    private static final Pattern EXCLUDED_TERMS = Pattern.compile(
            "\\b(?:forcément|nécessairement|wikipedia|wikipédia)\\b");

    private AutomaticFormalRouter() { }

    public static Optional<FrenchStructuredTranslator.Translation> recognize(String input) {
        if (input == null || input.isBlank() || input.length() > 8000 || input.stripLeading().startsWith(":")) {
            return Optional.empty();
        }
        if (DirectiveActs.classify(input).isPresent()) {
            return Optional.empty();
        }
        // Quoted material, modalities and documentary requests need their established routes.
        String lower = input.toLowerCase(Locale.ROOT);
        if (input.chars().anyMatch(c -> c == '"' || c == '«' || c == '»' || c == '“' || c == '”')
                || EXCLUDED_TERMS.matcher(lower).find()) {
            return Optional.empty();
        }
        var translated = new FrenchStructuredTranslator().translate(input);
        if (!translated.complete() || translated.problem().orElseThrow().premises().isEmpty()) {
            return Optional.empty();
        }
        // A parsed substring must never stand in for the whole request.
        if (translated.segments().stream().filter(FrenchStructuredTranslator.Segment::question).count() != 1) {
            return Optional.empty();
        }
        int cursor = 0;
        for (var segment : translated.segments()) {
            if (!segment.interpreted() || segment.start() < cursor || segment.end() > input.length()
                    || segment.end() <= segment.start()
                    || !input.substring(segment.start(), segment.end()).equals(segment.source())
                    || !FrenchStructuredTranslator.isLayoutGap(input, cursor, segment.start())) {
                return Optional.empty();
            }
            cursor = segment.end();
        }
        if (!input.substring(cursor).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(translated);
    }
}
