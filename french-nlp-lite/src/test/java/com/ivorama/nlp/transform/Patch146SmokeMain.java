package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import java.util.List;
import java.util.Optional;
public final class Patch146SmokeMain {
 public static void main(String[] args) {
  var tsv = new ResourceSynonymProvider();
  SynonymProvider fallback = new FallbackSynonymProvider(tsv, (token, register) -> Optional.of("secours"));
  var known = new TokenData(1,"chat","NOUN");
  var unknown = new TokenData(2,"inconnu","NOUN");
  require(fallback.synonym(known,SynonymRegister.COURANT).equals(Optional.of("félin")), "TSV priorité");
  require(fallback.synonym(unknown,SynonymRegister.COURANT).equals(Optional.of("secours")), "repli");
  require(fallback.isCuratedCandidate(known,SynonymRegister.COURANT,"félin"), "origine TSV");
  require(!fallback.isCuratedCandidate(unknown,SynonymRegister.COURANT,"secours"), "origine repli");
  var validator = new SynonymCandidateValidator();
  require(validator.isAcceptable(new TokenData(1,"noir","ADJ"),"sombre",true), "adjectif TSV");
  require(!validator.isAcceptable(new TokenData(1,"jeune","ADJ"),"bas",false), "adjectif non validé");
  require(!validator.isAcceptable(new TokenData(1,"étoiles","NOUN"),"astre",false), "pluriel");
  require(SentenceRenderer.render(List.of("dans","le","ancien","jardin",".")).equals("Dans l'ancien jardin."), "élision");
  System.out.println("PATCH 1.4.6: 8/8 tests OK");
 }
 private static void require(boolean condition, String label) {if (!condition) throw new AssertionError(label);}
}
