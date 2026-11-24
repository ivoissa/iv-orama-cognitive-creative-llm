package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** Bounded distributive grammar. NLP proposes annotations; declared grammar checks all words.
 * No pronoun binding, general plural assertion, tense conversion or guessed lemma is admitted. */
public final class NlpFormalBridge {
 public record Proposal(int start,int end,String normalized,String rule) {}
 private static final Pattern FORM=Pattern.compile("^(.+?) sont (chacun|chacune) ([\\p{L}][\\p{L}-]*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 private static final Pattern SUBJECT=Pattern.compile("^(le|la) ([\\p{L}][\\p{L}-]*) ([a-z][a-z0-9]*[0-9][a-z0-9]*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 private final FrenchNlpReader reader=new FrenchNlpReader();
 private final CopularPropertyLexicon lexicon=CopularPropertyLexicon.defaults();
 public Optional<Proposal> propose(String source,FrenchStructuredTranslator.Segment segment){
  String raw=source.substring(segment.start(),segment.end());
  String normalized=raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
  var form=FORM.matcher(normalized);if(!form.matches())return Optional.empty();
  // Only a declarative full stop, semicolon, newline or document end is admitted.
  if(segment.end()<source.length()&&".!?;\n".indexOf(source.charAt(segment.end()))<0)return Optional.empty();
  if(segment.end()<source.length()&&"!?".indexOf(source.charAt(segment.end()))>=0)return Optional.empty();
  String[] subjects=form.group(1).split(" et ",-1);
  if(subjects.length<2||subjects.length>8||new HashSet<>(List.of(subjects)).size()!=subjects.length)return Optional.empty();
  var singulars=new ArrayList<String>();boolean allFeminine=true;
  for(String subject:subjects){
   var m=SUBJECT.matcher(subject);if(!m.matches())return Optional.empty();
   var gender=m.group(1).equals("la")?SemanticLexicon.Gender.FEMININE:SemanticLexicon.Gender.MASCULINE;
   allFeminine&=gender==SemanticLexicon.Gender.FEMININE;
   singulars.add(subject);
  }
  if(!form.group(2).equals(allFeminine?"chacune":"chacun"))return Optional.empty();
  var pluralGender=allFeminine?SemanticLexicon.Gender.FEMININE:SemanticLexicon.Gender.MASCULINE;
  // The plural has to belong to one declared concept, including gender agreement.
  if(lexicon.distributiveSingular(form.group(3),pluralGender).isEmpty())return Optional.empty();
  var clauses=new ArrayList<String>();
  for(String subject:singulars){
   var gender=subject.startsWith("la ")?SemanticLexicon.Gender.FEMININE:SemanticLexicon.Gender.MASCULINE;
   // Mixed gender requires the same declared concept's other gender, not a guessed form.
   var formForGender=singularForm(form.group(3),pluralGender,gender);
   if(formForGender.isEmpty())return Optional.empty();
   clauses.add(subject+" est "+formForGender.orElseThrow());
  }
  List<FrenchNlpReader.Sentence> sentences;
  try{sentences=reader.read(raw);}catch(FrenchNlpReader.Unavailable|FrenchNlpReader.Limit ex){return Optional.empty();}
  if(sentences.size()!=1)return Optional.empty();
  var tokens=sentences.getFirst().tokens();
  // NLP annotation is necessary, never sufficient, and no numeric confidence is a truth test.
  long copulas=tokens.stream().filter(t->t.surface().equalsIgnoreCase("sont")&&Set.of("AUX","VERB").contains(t.pos())&&t.lemma().equalsIgnoreCase("être")).count();
  if(copulas!=1||tokens.stream().noneMatch(t->t.surface().equalsIgnoreCase(form.group(3))&&Set.of("ADJ","VERB").contains(t.pos())))return Optional.empty();
  String reading=String.join(" et ",clauses);
  // Reuse the independent existing strict reader for the entire proposed formula.
  var checked=new FrenchStructuredTranslator().analyzeText(reading+".");
  if(!checked.unresolved().isEmpty()||checked.propositions().size()!=1)return Optional.empty();
  return Optional.of(new Proposal(segment.start(),segment.end(),reading,"NAMED_DISTRIBUTIVE_COORDINATION"));
 }
 private Optional<String> singularForm(String plural,SemanticLexicon.Gender pluralGender,SemanticLexicon.Gender target){
  return lexicon.distributiveSingular(plural,pluralGender,target);
 }
}
