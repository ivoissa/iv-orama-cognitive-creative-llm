package com.ivorama.nlp.transform;
import java.util.List;
public record TextTransformationResult(String original, String transformed,
                                       List<TransformationResult> sentences) {
 public TextTransformationResult { sentences = List.copyOf(sentences); }
}
