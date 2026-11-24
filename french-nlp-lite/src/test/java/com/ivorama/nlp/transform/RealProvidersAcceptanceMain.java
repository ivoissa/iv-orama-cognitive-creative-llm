package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Vérifie le choix commun avec trois fournisseurs réels, sans CoreNLP/JWI. */
public final class RealProvidersAcceptanceMain {
 public static void main(String[] args) {
  var omw=new OmwFrenchSynonymProvider();
  var wolf=WolfXmlSynonymProvider.bundled();
  var tsv=new ResourceSynonymProvider();
  var combined=new CombinedSynonymProvider(List.of(
      new CombinedSynonymProvider.Source("OMW",omw),
      new CombinedSynonymProvider.Source("WOLF",wolf),
      new CombinedSynonymProvider.Source("TSV",tsv)),omw);
  var noun=new TokenData(1,"rivière","NOUN");
  var context=new SentenceAnalysis("La rivière traverse la vallée.",List.of(noun,new TokenData(2,"vallée","NOUN")),1,null,List.of(),List.of());
  var ranked=combined.explain(noun,SynonymRegister.COURANT,context);
  System.out.println("Classement réel: "+ranked);
  if(ranked.isEmpty()) throw new AssertionError("Aucun synonyme malgré les ressources");
  if(ranked.size()<2) throw new AssertionError("Les synonymes multiples ne sont pas fusionnés: "+ranked);
  var verb=new TokenData(1,"rivière","VERB");
  var verbRanked=combined.explain(verb,SynonymRegister.COURANT,context);
  System.out.println("Même lexème, POS VERB: "+verbRanked);
  if(!verbRanked.isEmpty()) throw new AssertionError("Contamination inter-POS: "+verbRanked);
  var rain=new TokenData(1,"pluie","NOUN");
  var rainContext=new SentenceAnalysis("La pluie tombe.",List.of(rain),1,null,List.of(),List.of());
  var rainCandidates=combined.explain(rain,SynonymRegister.COURANT,rainContext);
  if(rainCandidates.stream().anyMatch(c->c.word().equals("pleuvoir")))
    throw new AssertionError("Verbe homographe mal étiqueté retenu comme nom: "+rainCandidates);
  System.out.println("pluie/NOUN (sans pleuvoir): "+rainCandidates);
  System.out.println("REAL PROVIDERS: OK");
 }
}
