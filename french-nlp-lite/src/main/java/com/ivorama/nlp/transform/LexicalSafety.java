package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;

/** Garde-fous syntaxiques génériques. Aucun mot du corpus n'est codé dans cette classe. */
final class LexicalSafety {
    private LexicalSafety() {}

    /**
     * Un groupe adverbe + nom est conservé tant qu'une analyse de dépendances
     * suffisamment précise n'est pas exposée au moteur. Ce n'est pas une
     * reconnaissance sémantique exhaustive des expressions temporelles.
     */
    static boolean isProtectedExpression(SentenceAnalysis analysis, TokenData token) {
        if(java.util.Set.of("PROPN","NPP").contains(token.pos()))return true;
        if(token.dependency().startsWith("fixed")||token.dependency().startsWith("flat")||token.dependency().equals("compound"))return true;
        if(analysis.tokens().stream().anyMatch(t->t.headIndex()==token.index()&&(t.dependency().startsWith("fixed")||t.dependency().startsWith("flat")||t.dependency().equals("compound"))))return true;
        if (!token.isNoun()) return false;
        if(analysis.tokens().stream().anyMatch(t -> (t.headIndex()==token.index() && t.dependency().startsWith("nummod")) || (t.index()==token.index()-1 && (t.pos().equals("NUM") || t.word().matches("[0-9]+"))))) return true;
        // Bare noun complements introduced by de/d' can form lexical units.
        // Without a phrase lexicon, do not rewrite their head or dependent separately.
        for(var dependent:analysis.tokens())if(dependent.isNoun()&&dependent.dependency().startsWith("nmod")&&(dependent.index()==token.index()||dependent.headIndex()==token.index())){
            boolean bare=analysis.tokens().stream().noneMatch(t->t.headIndex()==dependent.index()&&t.dependency().equals("det"));
            boolean genitive=analysis.tokens().stream().anyMatch(t->t.headIndex()==dependent.index()&&t.dependency().equals("case")&&java.util.Set.of("de","d'","d’").contains(t.word().toLowerCase(java.util.Locale.ROOT)));
            if(bare&&genitive)return true;
        }
        return analysis.tokens().stream().anyMatch(t -> t.index() == token.index() - 1
                && ("ADV".equals(t.pos()) || t.pos().startsWith("ADV")));
    }

    /**
     * Un déterminant (y compris une contraction comme « au ») ne constitue
     * pas en soi un motif de rejet. Le candidat doit être validé par la
     * chaîne lexicale et reconnu par le fournisseur ; aucun accord de genre
     * n'est prétendu ici en l'absence de traits morphologiques fiables.
     */
    static boolean isSafeReplacement(SentenceAnalysis analysis, TokenData token,
                                     String candidate, SynonymProvider provider,
                                     SynonymRegister register) {
        if (!token.isNoun()) return true;
        return provider.assess(token,register,candidate,analysis).allowed();
    }
}
