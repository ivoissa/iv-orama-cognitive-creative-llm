package com.ivorama.nlp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record Constituent(ConstituentRole role, List<Integer> tokenIndices, String sourceRelation) {
    public Constituent {
        role = Objects.requireNonNull(role, "role");
        sourceRelation = Objects.requireNonNullElse(sourceRelation, "");
        LinkedHashSet<Integer> unique = new LinkedHashSet<>(Objects.requireNonNull(tokenIndices, "tokenIndices"));
        tokenIndices = unique.stream().sorted().toList();
        if (tokenIndices.isEmpty()) throw new IllegalArgumentException("Un constituant ne peut pas être vide");
    }

    public int start() {
        return tokenIndices.get(0);
    }

    public int end() {
        return tokenIndices.get(tokenIndices.size() - 1);
    }

    public boolean contains(int tokenIndex) {
        return tokenIndices.contains(tokenIndex);
    }

    public static Constituent of(ConstituentRole role, String sourceRelation, Collection<Integer> indices) {
        return new Constituent(role, new ArrayList<>(indices), sourceRelation);
    }

    public static List<Constituent> sorted(Collection<Constituent> constituents) {
        return constituents.stream().sorted(Comparator.comparingInt(Constituent::start)).toList();
    }
}
