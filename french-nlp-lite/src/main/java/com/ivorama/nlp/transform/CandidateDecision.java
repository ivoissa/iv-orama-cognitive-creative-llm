package com.ivorama.nlp.transform;
/** Auditable evidence, never a probability of contextual equivalence. */
public record CandidateDecision(boolean allowed,String reason,double score,String sense,String evidence) {
 public CandidateDecision{if(!Double.isFinite(score)||score<0||score>1)throw new IllegalArgumentException("Score outside [0,1]");}
 public static CandidateDecision reject(String why){return new CandidateDecision(false,why,0,"","");}
 public static CandidateDecision trusted(){return new CandidateDecision(true,"EDITED_LEXICAL_PAIR",1,"edited","Explicit provider assertion; morphology still mandatory");}
}
