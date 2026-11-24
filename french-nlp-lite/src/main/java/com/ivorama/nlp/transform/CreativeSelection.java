package com.ivorama.nlp.transform;
import java.util.*;
/** Deterministic rank displacement within admissible Robert candidates only. */
final class CreativeSelection {
 private CreativeSelection() {}
 static boolean groupAllowed(double fit,double best,double freedom){return freedom>0 && fit>0 && fit>=best*(1-.75*freedom);}
 static List<DiscoGroupSynonymProvider.Detail> order(List<DiscoGroupSynonymProvider.Detail> input,double freedom){
  var sorted=new ArrayList<>(input);sorted.removeIf(d->!d.reason().equals("ELIGIBLE"));sorted.sort(Comparator.comparingDouble(DiscoGroupSynonymProvider.Detail::score).reversed().thenComparing(DiscoGroupSynonymProvider.Detail::candidate));
  var unique=new LinkedHashMap<String,DiscoGroupSynonymProvider.Detail>();for(var d:sorted)unique.putIfAbsent(d.candidate(),d);sorted=new ArrayList<>(unique.values());
  if(sorted.isEmpty())return List.of();
  double best=sorted.get(0).score();
  sorted.sort(Comparator.comparingDouble((DiscoGroupSynonymProvider.Detail d)->utility(d,freedom,best)).reversed().thenComparing(DiscoGroupSynonymProvider.Detail::candidate));
  return List.copyOf(sorted);
 }
 static double utility(DiscoGroupSynonymProvider.Detail d,double freedom,double best){
  double quality=best>0?d.score()/best:0;
  return .45*quality+.35*d.retention()+.20*(1-Math.abs((1-d.lexicalFit())-freedom));
 }
}
