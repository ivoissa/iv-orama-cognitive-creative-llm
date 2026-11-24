package com.ivorama.nlp.transform;
/** Equal contribution is an uncalibrated symmetry default, validated by ablation. */
public record RobertSettings(boolean enabled,double contextWeight,double neighborhoodWeight,double minContext,double minMargin,boolean fallbackToOmw,boolean requireReviewedSource){
 public RobertSettings(boolean enabled,double context,double neighborhood,double minContext,double minMargin){this(enabled,context,neighborhood,minContext,minMargin,false,true);}
 public RobertSettings(boolean enabled,double context,double neighborhood,double minContext,double minMargin,boolean fallback){this(enabled,context,neighborhood,minContext,minMargin,fallback,true);}
 public RobertSettings{if(!Double.isFinite(contextWeight)||!Double.isFinite(neighborhoodWeight)||!Double.isFinite(contextWeight+neighborhoodWeight)||contextWeight<0||neighborhoodWeight<0||contextWeight+neighborhoodWeight<=0||!Double.isFinite(minContext)||!Double.isFinite(minMargin)||minContext<0||minContext>1||minMargin<0||minMargin>1)throw new IllegalArgumentException("Invalid semantic settings");}
 public static RobertSettings defaults(){return new RobertSettings(true,1,1,0.01,0.01);}
}
