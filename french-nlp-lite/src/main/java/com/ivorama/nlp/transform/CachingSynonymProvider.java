package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import com.ivorama.nlp.core.SentenceAnalysis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Le fichier ne stocke que les requêtes sans contexte, selon le format historique.
 * Le cache mémoire est borné et isolé par document et analyse.
 */
public final class CachingSynonymProvider implements SynonymProvider {
    private record Query(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        @Override public boolean equals(Object other) {
            return other instanceof Query query && context == query.context
                    && register == query.register && token.equals(query.token);
        }

        @Override public int hashCode() {
            return 31 * (31 * token.hashCode() + register.hashCode()) + System.identityHashCode(context);
        }
    }
    private record Assessment(Query query, String candidate) {}

    private List<SentenceAnalysis> document = List.of();
    private final BoundedCache<Query, Optional<String>> contextual = new BoundedCache<>(512);
    private final BoundedCache<Query, List<String>> inventories = new BoundedCache<>(512);
    private final BoundedCache<Query, List<String>> selections = new BoundedCache<>(512);
    private final BoundedCache<Assessment, CandidateDecision> assessments = new BoundedCache<>(4096);

    @Override public synchronized void prepareDocument(List<SentenceAnalysis> analyses) {
        var next = List.copyOf(analyses);
        delegate.prepareDocument(analyses);
        if (!document.equals(next)) {
            contextual.clear();
            inventories.clear();
            selections.clear();
            assessments.clear();
            document = next;
        }
    }

    private boolean useMemory(SentenceAnalysis context) {
        return context != null && !Boolean.getBoolean("ivorama.cache.disabled");
    }
    private final SynonymProvider delegate;
    private final Path file;
    private final boolean readEnabled;
    private final boolean writeEnabled;
    private final Map<String,String> entries = new HashMap<>();

    public CachingSynonymProvider(SynonymProvider delegate, Path file,
                                  boolean readEnabled, boolean writeEnabled) {
        this.delegate = delegate;
        this.file = file;
        this.readEnabled = readEnabled;
        this.writeEnabled = writeEnabled;
        if (readEnabled && Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] a = line.split("\t", 4);
                    if (a.length == 4) entries.put(a[0] + "|" + a[1] + "|" + a[2], a[3]);
                }
            } catch (IOException e) { throw new IllegalStateException("Lecture cache impossible",e); }
        }
    }

    @Override public synchronized Optional<String> synonym(TokenData token, SynonymRegister register) {
        String key = token.word().toLowerCase(Locale.ROOT) + "|" + token.pos() + "|" + register.name();
        if (readEnabled && entries.containsKey(key)) return Optional.of(entries.get(key));
        Optional<String> result = delegate.synonym(token, register);
        if (result.isPresent() && writeEnabled) {
            try {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, token.word().toLowerCase(Locale.ROOT) + "\t"
                        + token.pos() + "\t" + register.name() + "\t" + result.get() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                entries.put(key, result.get());
            } catch (IOException e) { throw new IllegalStateException("Écriture cache impossible", e); }
        }
        return result;
    }

    @Override public synchronized Optional<String> synonym(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        if (!useMemory(context)) return delegate.synonym(token, register, context);
        return contextual.get(new Query(token, register, context), key -> delegate.synonym(token, register, context));
    }

    @Override public synchronized List<String> candidates(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        if (!useMemory(context)) return delegate.candidates(token, register, context);
        return selections.get(new Query(token, register, context), key -> List.copyOf(delegate.candidates(token, register, context)));
    }

    @Override public synchronized List<String> inventory(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        if (!useMemory(context)) return delegate.inventory(token, register, context);
        return inventories.get(new Query(token, register, context), key -> List.copyOf(delegate.inventory(token, register, context)));
    }

    @Override public List<String> dictionaryOrder(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        return delegate.dictionaryOrder(token, register, context);
    }

    @Override public CandidateDecision assessUnchecked(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
        return delegate.assessUnchecked(token, register, candidate, context);
    }

    @Override public CandidateDecision assessDirect(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
        return delegate.assessDirect(token, register, candidate, context);
    }

    @Override public synchronized CandidateDecision assess(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
        if (!useMemory(context)) return delegate.assess(token, register, candidate, context);
        return assessments.get(new Assessment(new Query(token, register, context), candidate),
                key -> delegate.assess(token, register, candidate, context));
    }

    @Override public synchronized boolean compatibleAddition(TokenData token, String current, String candidate, SentenceAnalysis context) {
        return delegate.compatibleAddition(token, current, candidate, context);
    }

    @Override public boolean isCuratedCandidate(TokenData token, SynonymRegister register, String candidate) {
        return delegate.isCuratedCandidate(token, register, candidate);
    }

    @Override public boolean supportsRegisters() { return delegate.supportsRegisters(); }
}
