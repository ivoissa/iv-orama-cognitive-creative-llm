package com.ivorama.nlp.transform;
import java.nio.file.Path;
/** Operational thresholds are explicit heuristics, not calibrated probabilities. */
public record DiscoSettings(boolean enabled,Path directory,double minGroupScore,double minMargin,double minRetention,int semanticFreedom, Integer adjectiveSemanticFreedom, Integer nounSemanticFreedom){
 public DiscoSettings(boolean enabled,Path directory,double minGroupScore,double minMargin,double minRetention){this(enabled,directory,minGroupScore,minMargin,minRetention,0);}
 public DiscoSettings(boolean enabled,Path directory,double minGroupScore,double minMargin,double minRetention,int semanticFreedom){this(enabled,directory,minGroupScore,minMargin,minRetention,semanticFreedom,null,null);}
 public DiscoSettings{validate(adjectiveSemanticFreedom);validate(nounSemanticFreedom);if(directory==null||!Double.isFinite(minGroupScore)||!Double.isFinite(minMargin)||!Double.isFinite(minRetention)||minGroupScore<0||minGroupScore>1||minMargin<0||minMargin>1||minRetention<0||minRetention>1||semanticFreedom<0||semanticFreedom>100)throw new IllegalArgumentException("Invalid DISCO settings");}
 private static void validate(Integer value){if(value!=null&&(value<0||value>100))throw new IllegalArgumentException("Semantic freedom must be in [0,100]");}
 public int semanticFreedom(String pos){return "a".equals(pos)&&adjectiveSemanticFreedom!=null?adjectiveSemanticFreedom:"n".equals(pos)&&nounSemanticFreedom!=null?nounSemanticFreedom:semanticFreedom;}
 /** An explicit zero on a word-class slider disables edits for that class. */
 boolean editsDisabled(String pos) {
  return switch (pos) {
   case "a" -> Integer.valueOf(0).equals(adjectiveSemanticFreedom);
   case "n" -> Integer.valueOf(0).equals(nounSemanticFreedom);
   default -> false;
  };
 }
 public double freedom(String pos){return semanticFreedom(pos)/100d;}
 public double effectiveRetention(String pos){return minRetention*(1-.75*freedom(pos));}
 public double effectiveMargin(String pos){return minMargin*(1-freedom(pos));}
 public double freedom(){return semanticFreedom/100d;}
 public double effectiveRetention(){return minRetention*(1-.75*freedom());}
 public double effectiveMargin(){return minMargin*(1-freedom());}
 public static DiscoSettings defaults(){return new DiscoSettings(false,Path.of("data/disco/fr-general-20151126-lm-sim"),.01,.02,.8);}
}
