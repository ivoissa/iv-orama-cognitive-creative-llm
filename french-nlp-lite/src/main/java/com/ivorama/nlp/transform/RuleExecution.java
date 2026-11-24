package com.ivorama.nlp.transform;
/** Actual effect is distinct from a configured/applicable legacy policy. */
public record RuleExecution(String parameter,boolean enabled,String status,String reason,int applications){}
