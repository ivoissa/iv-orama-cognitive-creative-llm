package com.ivorama.nlp.transform;

import java.util.List;

public record TransformationConfig(
        boolean synonymeFamilierEnergie,
        boolean synonymeCourantEnergie,
        boolean synonymeSoutenuEnergie,
        boolean yodaMode,
        boolean emphaseColores,
        boolean joyeuseCirconstanceMode,
        boolean respirationCoolMode,
        boolean stoiquePhiloMode,
        long randomSeed,
        boolean replaceNounsEnabled, boolean emphaseMode, List<NounEmphasisRule.Adjective> nounEmphasis, boolean directDictionary, int emphasisFallbackLimit,
        int adjectiveSubstitutionRate, int nounSubstitutionRate, boolean candidateRejectionsEnabled) {
    public TransformationConfig {
        nounEmphasis = List.copyOf(nounEmphasis);
        if (adjectiveSubstitutionRate < 0 || adjectiveSubstitutionRate > 100
                || nounSubstitutionRate < 0 || nounSubstitutionRate > 100) {
            throw new IllegalArgumentException("Les taux de substitution doivent être compris entre 0 et 100");
        }
        if (emphasisFallbackLimit < 0) throw new IllegalArgumentException("emphasis_fallback_limit doit être positif ou nul");
    }

    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic,
                                long seed, boolean nouns, boolean emphasis,
                                List<NounEmphasisRule.Adjective> nounEmphasis, boolean directDictionary,
                                int emphasisFallbackLimit, int adjectiveRate, int nounRate) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic, seed, nouns,
                emphasis, nounEmphasis, directDictionary, emphasisFallbackLimit, adjectiveRate, nounRate, true);
    }

    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic,
                                long seed, boolean nouns, boolean emphasis,
                                List<NounEmphasisRule.Adjective> nounEmphasis, boolean directDictionary,
                                int emphasisFallbackLimit) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic,
                seed, nouns, emphasis, nounEmphasis, directDictionary, emphasisFallbackLimit, 100, 100);
    }

    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic,
                                long seed, boolean nouns, boolean emphasis,
                                List<NounEmphasisRule.Adjective> nounEmphasis, boolean directDictionary) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic,
                seed, nouns, emphasis, nounEmphasis, directDictionary, 1);
    }

    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic,
                                long seed, boolean nouns, boolean emphasis, List<NounEmphasisRule.Adjective> nounEmphasis) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic, seed, nouns, emphasis, nounEmphasis, false);
    }

    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic,
                                long seed, boolean nouns, boolean emphasis) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic, seed, nouns, emphasis, List.of());
    }
    public TransformationConfig(boolean familiar,boolean common,boolean formal,boolean yoda,boolean adjectives,boolean cc,boolean commas,boolean stoic,long seed,boolean nouns){this(familiar,common,formal,yoda,adjectives,cc,commas,stoic,seed,nouns,false);}


    public TransformationConfig(boolean familiar, boolean common, boolean formal, boolean yoda,
                                boolean adjectives, boolean cc, boolean commas, boolean stoic, long seed) {
        this(familiar, common, formal, yoda, adjectives, cc, commas, stoic, seed, true);
    }

    public SynonymRegister requestedRegister() {
        int enabled = (synonymeFamilierEnergie ? 1 : 0)
                + (synonymeCourantEnergie ? 1 : 0)
                + (synonymeSoutenuEnergie ? 1 : 0);
        if (enabled > 1) return SynonymRegister.COURANT;
        if (synonymeSoutenuEnergie) return SynonymRegister.SOUTENU;
        if (synonymeFamilierEnergie) return SynonymRegister.FAMILIER;
        return SynonymRegister.COURANT;
    }

    public static TransformationConfig defaults() {
        return new TransformationConfig(false, true, false, false, false, false, true, false, 20261107L);
    }
}
