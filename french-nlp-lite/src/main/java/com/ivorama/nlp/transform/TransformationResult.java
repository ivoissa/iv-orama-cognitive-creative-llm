package com.ivorama.nlp.transform;

import java.util.List;

public record TransformationResult(
        String original,
        String transformed,
        SynonymRegister requestedRegister,
        SynonymRegister effectiveRegister,
        List<String> appliedRules,
        List<Replacement> replacements,List<LexicalAttempt> lexicalAttempts,List<RuleExecution> executions) {
    public TransformationResult(String original,String transformed,SynonymRegister requested,SynonymRegister effective,List<String> rules,List<Replacement> replacements){this(original,transformed,requested,effective,rules,replacements,List.of(),List.of());}
    public TransformationResult(String original,String transformed,SynonymRegister requested,SynonymRegister effective,List<String> rules,List<Replacement> replacements,List<LexicalAttempt> attempts){this(original,transformed,requested,effective,rules,replacements,attempts,List.of());}
}
