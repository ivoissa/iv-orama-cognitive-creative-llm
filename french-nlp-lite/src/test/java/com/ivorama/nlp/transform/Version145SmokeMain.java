package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import java.util.Optional;
public final class Version145SmokeMain {
 public static void main(String[] args) {
  var tsv = new ResourceSynonymProvider();
  var wolf = WolfXmlSynonymProvider.bundled();
  var provider = new FallbackSynonymProvider(tsv, wolf);
  var cat = new TokenData(1, "chat", "NOUN");
  if (!provider.synonym(cat, SynonymRegister.COURANT).equals(Optional.of("félin"))) throw new AssertionError("TSV priority");
  var unknown = new TokenData(1, "chance", "NOUN");
  if (tsv.synonym(unknown, SynonymRegister.COURANT).isPresent()) throw new AssertionError("fixture not absent");
  if (provider.synonym(unknown, SynonymRegister.COURANT).isEmpty()) throw new AssertionError("WOLF fallback");
  var v = new SynonymCandidateValidator();
  if (v.isAcceptable(new TokenData(1,"jeune","ADJ"),"bas")) throw new AssertionError("unsafe adjective accepted");
  if (v.isAcceptable(new TokenData(1,"étoiles","NOUN"),"astre")) throw new AssertionError("unsafe plural accepted");
  System.out.println("V1.4.5 lexical/filter smoke: 4/4 OK; WOLF synsets="+wolf.synsetCount());
 }
}
