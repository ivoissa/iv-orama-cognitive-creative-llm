package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import com.ivorama.nlp.core.SentenceAnalysis;

import java.util.Optional;
import java.util.List;

public interface SynonymProvider {
    default boolean compatibleAddition(TokenData token,String current,String candidate,SentenceAnalysis context){return true;}
    default void prepareDocument(java.util.List<SentenceAnalysis> analyses) {}
    default List<String> dictionaryOrder(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        return inventory(token, register, context);
    }

    default CandidateDecision assessUnchecked(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
        return assessDirect(token, register, candidate, context);
    }
    default CandidateDecision assessDirect(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
        return assess(token, register, candidate, context);
    }
    default CandidateDecision assess(TokenData token,SynonymRegister register,String candidate,SentenceAnalysis context){return isCuratedCandidate(token,register,candidate)?CandidateDecision.trusted():CandidateDecision.reject("NO_SEMANTIC_AUTHORIZATION");}
    default List<String> inventory(TokenData token,SynonymRegister register,SentenceAnalysis context){return candidates(token,register,context);}
    Optional<String> synonym(TokenData token, SynonymRegister register);

    default Optional<String> synonym(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        return synonym(token, register);
    }

    /** Tous les candidats disponibles; un fournisseur ne décide plus seul de l'abstention. */
    default List<String> candidates(TokenData token, SynonymRegister register, SentenceAnalysis context) {
        return synonym(token, register, context).stream().toList();
    }

    /**
     * false = le fournisseur ne sait pas garantir le registre demandé;
     * le moteur force alors COURANT, conformément à la règle du projet.
     */
    default boolean isCuratedCandidate(TokenData token, SynonymRegister register, String candidate) { return false; }

    default boolean supportsRegisters() {
        return false;
    }
}
