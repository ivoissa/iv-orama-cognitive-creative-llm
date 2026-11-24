package fr.ivorama.cognitive;

import java.util.*;

/** Bounded lexical centrality, original sentences only; not a semantic paraphraser. */
public final class ExtractiveTextSummary {
 private ExtractiveTextSummary() {}
 public record Result(List<Integer> selected,List<Integer> omitted,String text,List<String> warnings){
  public Result{selected=List.copyOf(selected);omitted=List.copyOf(omitted);warnings=List.copyOf(warnings);}
  public String describe(){return "Résumé extractif (sélection lexicale, sans reformulation ni compréhension complète revendiquée) :\n"+text+"\nPhrases retenues : "+selected+" ; omises : "+omitted+".\n"+String.join("\n",warnings);}
 }
 public static Result summarize(FrenchDiscourse.Document d){
  int n=d.sentences().size();var frequencies=new HashMap<String,Integer>();
  var keys=new ArrayList<Set<String>>();
  for(var sentence:d.sentences()){
   var set=new HashSet<String>();for(var token:sentence.tokens())if(Set.of("NOUN","PROPN","VERB","ADJ").contains(token.pos()))set.add(token.lemma().toLowerCase(Locale.ROOT));
   keys.add(set);for(String key:set)frequencies.merge(key,1,Integer::sum);
  }
  var eligible=new ArrayList<Integer>();var dependencies=new HashMap<Integer,Set<Integer>>();var warnings=new ArrayList<String>();
  for(int i=0;i<n;i++){
   if(!d.clauses().get(i).limitations().isEmpty())continue;
   boolean safe=true;var deps=new TreeSet<Integer>();var sentence=d.sentences().get(i);
   for(var r:d.references())if(r.pronoun().start()>=sentence.start()&&r.pronoun().end()<=sentence.end()){
    if(!r.status().equals("UNIQUE_AGREEMENT_CANDIDATE")){safe=false;break;}
    var m=d.mentions().stream().filter(x->x.id().equals(r.candidates().getFirst())).findFirst().orElseThrow();
    for(int j=0;j<i;j++)if(m.source().start()>=d.sentences().get(j).start()&&m.source().end()<=d.sentences().get(j).end())deps.add(j);
   }
   if(safe){eligible.add(i);dependencies.put(i,deps);}
  }
  // A dependency must itself be eligible. Iterate to a fixed point for reference chains.
  boolean changed;do{changed=eligible.removeIf(i->!eligible.containsAll(dependencies.get(i)));}while(changed);
  var ranked=new ArrayList<>(eligible);
  ranked.sort(Comparator.<Integer>comparingDouble(i->-keys.get(i).stream().mapToInt(k->frequencies.get(k)).sum()/(double)Math.max(1,keys.get(i).size())).thenComparingInt(i->i));
  var chosen=new TreeSet<Integer>();
  for(int i:ranked){var required=new TreeSet<Integer>();collect(i,dependencies,required);required.addAll(chosen);if(required.size()<=2)chosen.addAll(required);}
  var omitted=new ArrayList<Integer>();for(int i=0;i<n;i++)if(!chosen.contains(i))omitted.add(i);
  if(!omitted.isEmpty())warnings.add("Les phrases omises restent disponibles dans le document JSON ; aucun résumé exhaustif n'est garanti.");
  if(chosen.isEmpty())warnings.add("Aucun extrait admissible : structure ou référence insuffisamment résolue.");
  warnings.add("L'ordre du texte est conservé ; il ne prouve pas un ordre chronologique ou causal.");
  return new Result(new ArrayList<>(chosen),omitted,String.join("\n",chosen.stream().map(i->d.source().substring(d.sentences().get(i).start(),d.sentences().get(i).end())).toList()),warnings);
 }
 private static void collect(int i,Map<Integer,Set<Integer>> dependencies,Set<Integer> into){if(into.add(i))for(int j:dependencies.getOrDefault(i,Set.of()))collect(j,dependencies,into);}
}
