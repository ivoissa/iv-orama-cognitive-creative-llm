package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
/** Contexte minimal de validation, indépendant des six phrases de référence. */
public record CandidateValidation(TokenData original, String candidate, boolean curated,boolean morphologicallyAttested) {
 public CandidateValidation(TokenData original,String candidate,boolean curated){this(original,candidate,curated,false);}
}
