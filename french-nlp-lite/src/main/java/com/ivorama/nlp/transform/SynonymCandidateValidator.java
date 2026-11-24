package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import java.util.Locale;
import java.util.Set;
/** Filtre conservateur, sans prétention de désambiguïsation sémantique. */
public final class SynonymCandidateValidator {
 private static final Set<String> UNSAFE = Set.of("", "-", "?", "null");
 public boolean isAcceptableWithMorphology(TokenData original,String candidate){return candidate!=null&&!UNSAFE.contains(candidate.toLowerCase(Locale.ROOT))&&!candidate.equalsIgnoreCase(original.word())&&candidate.matches("[\\p{L}]+(?:[-'’][\\p{L}]+)*")&&!Set.of("PROPN","NPP").contains(original.pos());}
 public boolean isAcceptable(TokenData original, String candidate) {
  return isAcceptable(original, candidate, false);
 }
 public boolean isAcceptable(TokenData original, String candidate, boolean curatedSource) {
  if (candidate == null || candidate.isBlank()) return false;
  String word = original.word();
  String value = candidate.trim();
  if (UNSAFE.contains(value.toLowerCase(Locale.ROOT)) || value.equalsIgnoreCase(word)) return false;
  if (!value.matches("[\\p{L}]+(?:[-'][\\p{L}]+)*")) return false;
  // Sans données morphologiques fiables, les formes accordées ne sont pas modifiées.
  // Les équivalences explicitement éditées dans le TSV sont acceptées si la forme
  // est plausible ; les adjectifs provenant d'un WordNet non désambiguïsé sont refusés.
  if (original.isAdjective() && !curatedSource) return false;
  if (original.isAdjective() && !sameSimpleInflection(word, value)) return false;
  if (original.isNoun() && word.endsWith("s") && !value.endsWith("s")) return false;
  return true;
 }
 private static boolean sameSimpleInflection(String source, String candidate) {
  // Vérification prudente des marques explicites du pluriel et du féminin.
  String a = source.toLowerCase(Locale.ROOT), b = candidate.toLowerCase(Locale.ROOT);
  if (a.endsWith("es") && !b.endsWith("es")) return false;
  if (a.endsWith("s") && !b.endsWith("s")) return false;
  return true;
 }
}
