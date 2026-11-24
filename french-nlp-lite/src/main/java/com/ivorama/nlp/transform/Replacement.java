package com.ivorama.nlp.transform;

public record Replacement(int tokenIndex, String original, String replacement, String reason) {}
