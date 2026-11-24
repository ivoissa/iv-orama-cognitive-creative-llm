package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import edu.mit.jwi.Dictionary;
import edu.mit.jwi.IDictionary;
import edu.mit.jwi.item.IIndexWord;
import edu.mit.jwi.item.IWord;
import edu.mit.jwi.item.IWordID;
import edu.mit.jwi.item.POS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/**
 * Recherche dans un dictionnaire WordNet local au format Princeton compatible JWI.
 * IMPORTANT : fournir un dictionnaire FRANÇAIS compatible; le WordNet anglais
 * ne constitue pas une source valide de synonymes français.
 */
public final class WordNetSynonymProvider implements SynonymProvider, AutoCloseable {
    private final IDictionary dictionary;

    public WordNetSynonymProvider(Path dictionaryDirectory) {
        try {
            dictionary = new Dictionary(dictionaryDirectory.toUri().toURL());
            dictionary.setCharset(java.nio.charset.StandardCharsets.UTF_8);
            if(!dictionary.open())throw new IOException("JWI open returned false");
        } catch (IOException e) {
            throw new IllegalStateException("Dictionnaire WordNet inaccessible : " + dictionaryDirectory, e);
        }
    }

    @Override public Optional<String> synonym(TokenData token, SynonymRegister register) {
        POS pos = pos(token.pos());
        if (pos == null) return Optional.empty();
        String lemma = token.word();
        IIndexWord index = dictionary.getIndexWord(lemma.toLowerCase(Locale.ROOT), pos);
        if (index == null) return Optional.empty();
        for (IWordID id : index.getWordIDs()) {
            IWord word = dictionary.getWord(id);
            if (word == null) continue;
            Optional<String> candidate = word.getSynset().getWords().stream()
                    .map(IWord::getLemma).map(s -> s.replace('_', ' '))
                    .filter(s -> !s.equalsIgnoreCase(lemma))
                    .filter(s -> !s.equalsIgnoreCase(token.word()))
                    .filter(s -> !s.contains(" "))
                    .sorted(Comparator.naturalOrder()).findFirst();
            if (candidate.isPresent()) return candidate;
        }
        return Optional.empty();
    }

    @Override public java.util.List<String> candidates(TokenData token,SynonymRegister register,
            com.ivorama.nlp.core.SentenceAnalysis context) {
        POS part=pos(token.pos());
        if(part==null) return java.util.List.of();
        IIndexWord entry=dictionary.getIndexWord(token.word().toLowerCase(Locale.ROOT),part);
        if(entry==null) return java.util.List.of();
        java.util.Set<String> result=new java.util.TreeSet<>();
        for(IWordID id:entry.getWordIDs()) {
            IWord w=dictionary.getWord(id);
            if(w==null) continue;
            for(IWord candidate:w.getSynset().getWords()) {
                String lemma=candidate.getLemma().replace('_',' ');
                if(!lemma.equalsIgnoreCase(token.word()) && !lemma.contains(" ")
                    && lemma.matches("[\\p{L}]+(?:[-'][\\p{L}]+)*")) result.add(lemma);
            }
        }
        return java.util.List.copyOf(result);
    }

    private static POS pos(String tag) {
        if (tag == null) return null;
        String t = tag.toUpperCase(Locale.ROOT);
        if (t.startsWith("N") || t.equals("PROPN")) return POS.NOUN;
        if (t.startsWith("V")) return POS.VERB;
        if (t.startsWith("ADJ") || t.equals("A")) return POS.ADJECTIVE;
        if (t.startsWith("ADV")) return POS.ADVERB;
        return null;
    }

    @Override public void close() { dictionary.close(); }
}
