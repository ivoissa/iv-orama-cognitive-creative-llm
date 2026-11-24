package com.ivorama.nlp.transform;
/** Heuristic bounds, not probabilities; missing context never certifies strict synonymy. */
final class NeighborPolicy {
 private NeighborPolicy() {}
 static String reason(double freedom,double lexical,double context,double retention,double sourceContext,double minRetention,boolean duplicate,boolean forbidden){
  if(freedom<=0)return "STRICT_ROBERT_ONLY";
  if(duplicate)return "CANDIDATE_ALREADY_IN_CONTEXT";
  if(forbidden)return "FORBIDDEN_LEXICAL_RELATION";
  if(lexical<.35-.25*freedom)return "NEIGHBOR_SIMILARITY_TOO_LOW";
  if(sourceContext>0&&retention<minRetention)return "CONTEXT_DEGRADATION";
  if(context<=0||sourceContext<=0)return "NO_CONTEXT_SUPPORT";
  return "ELIGIBLE";
 }
}
