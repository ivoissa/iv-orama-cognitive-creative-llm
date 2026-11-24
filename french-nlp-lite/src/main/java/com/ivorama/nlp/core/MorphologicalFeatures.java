package com.ivorama.nlp.core;
/** Traits attestés, non inférés par des règles lexicales ad hoc. */
public record MorphologicalFeatures(String lemma, String pos, String gender, String number) {
    public MorphologicalFeatures {
        lemma = lemma == null ? "" : lemma;
        pos = pos == null ? "" : pos;
        gender = gender == null ? "" : gender;
        number = number == null ? "" : number;
    }
    public boolean hasAgreementEvidence() { return !gender.isBlank() && !number.isBlank(); }
}
