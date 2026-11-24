package fr.ivorama.cognitive.render;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import com.ivorama.nlp.transform.SentenceTransformerService;
import com.ivorama.nlp.transform.SynonymProviderFactory;
import com.ivorama.nlp.transform.TransformationConfig;
import com.ivorama.nlp.transform.TransformationConfigLoader;
import java.nio.file.Path;
import java.util.Objects;

/** Local, opt-in rendering of an already approved plain-text passage. */
public final class LiteTextRenderer {
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
}
