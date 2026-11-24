package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.util.*;

/** Diagnostic sur ressources OMW/WordNet reelles, sans reponse preprogrammee. */
public final class OmwRealResourceDiagnosticMain {
 public static void main(String[] args) {
  OmwFrenchSynonymProvider provider = new OmwFrenchSynonymProvider();
  List<String> samples = List.of("chat", "chien", "maison", "livre", "banque", "avocat", "pierre", "rapport", "pluie", "rivière");
  int suggestions = 0, abstentions = 0;
  for (String word : samples) {
   TokenData t = new TokenData(1, word, "NOUN");
   SentenceAnalysis context = new SentenceAnalysis(word + " .", List.of(t, new TokenData(2, ".", "PUNCT")),
      1, new Constituent(ConstituentRole.SUBJECT, List.of(1), "nsubj"), List.of(), List.of());
   Optional<String> proposal = provider.synonym(t, SynonymRegister.COURANT, context);
   if (proposal.isPresent()) suggestions++; else abstentions++;
   System.out.printf("OMW CONTEXTE MINIMAL %-12s -> %s%n", word, proposal.orElse("ABSTENTION"));
   if (proposal.isPresent() && proposal.get().equalsIgnoreCase(word)) throw new AssertionError("Synonyme identique");
  }
  System.out.printf("DIAGNOSTIC OMW: %d propositions / %d abstentions / %d cas%n", suggestions, abstentions, samples.size());
  // L'absence de contexte ne doit pas fabriquer un score Lesk pour les mots polysemiques.
  if (suggestions + abstentions != samples.size()) throw new AssertionError("Comptage invalide");
 }
}
