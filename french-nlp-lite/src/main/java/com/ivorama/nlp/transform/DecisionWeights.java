package com.ivorama.nlp.transform;
/** Coefficients du classement réellement disponibles en RC2; aucune fréquence fictive. */
public record DecisionWeights(double semantic,double agreement) {
 public DecisionWeights {
  if(!Double.isFinite(semantic)||!Double.isFinite(agreement)||!Double.isFinite(semantic+agreement)||semantic<0||agreement<0||semantic+agreement<=0)
   throw new IllegalArgumentException("Poids invalides");
 }
 public static DecisionWeights defaults(){return new DecisionWeights(0.8,0.2);}
}
