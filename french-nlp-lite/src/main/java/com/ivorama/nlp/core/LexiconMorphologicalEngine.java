package com.ivorama.nlp.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Attested Dicollecte/Grammalecte forms. No suffix-based invention of gender or inflection. */
public final class LexiconMorphologicalEngine implements MorphologicalEngine {
 public record Form(String word,String lemma,Set<String> tags) {
  public boolean matches(String pos){return switch(pos){case "NOUN"->tags.contains("nom");case "ADJ"->tags.contains("adj");case "DET"->tags.stream().anyMatch(t->t.startsWith("det"));default->false;};}
  public boolean gender(String g){return g.isEmpty()||tags.contains(g)||tags.contains("epi");}
  public boolean number(String n){return n.isEmpty()||tags.contains(n)||tags.contains("inv");}
 }
 private static final class Data {
  final Map<String,List<Form>> words=new HashMap<>(),lemmas=new HashMap<>();
  final Set<String> negative=new HashSet<>();
  final Set<String> indirectVerbForms=new HashSet<>(),animateNouns=new HashSet<>(),possibleAnimate=new HashSet<>();
  final Map<String,Set<String>> contextLemmas=new HashMap<>();
  Data(){
   try(var raw=LexiconMorphologicalEngine.class.getResourceAsStream("/lexicon/morphology.tsv")){
    if(raw==null)throw new IllegalStateException("Ressource morphologique absente");
    var digest=java.security.MessageDigest.getInstance("SHA-256");var in=new java.security.DigestInputStream(raw,digest);
    try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
     reader.readLine();String line;
     while((line=reader.readLine())!=null){var a=line.split("\t",-1);if(a.length!=3)throw new IllegalStateException("Morphologie TSV invalide");
      var tags=Set.copyOf(Arrays.asList(a[2].split(" ")));
      // Grammalecte Vtyp: position 4 of the raw info (after v) marks indirect transitivity.
      if(tags.stream().anyMatch(t->t.startsWith("v")&&t.length()>5&&t.charAt(5)=='n'))indirectVerbForms.add(a[0]);
      if(tags.contains("adv")||tags.stream().anyMatch(t->t.startsWith("v")))contextLemmas.computeIfAbsent(a[0],k->new HashSet<>()).add(a[1]);
      if(tags.stream().anyMatch(t->t.contains("neg")))negative.add(a[0]);
      if(!tags.contains("nom")&&!tags.contains("adj")&&tags.stream().noneMatch(t->t.startsWith("det")))continue;
      var form=new Form(a[0],a[1],tags);words.computeIfAbsent(a[0],k->new ArrayList<>()).add(form);lemmas.computeIfAbsent(a[1],k->new ArrayList<>()).add(form);
     }
    }
    try(var hash=LexiconMorphologicalEngine.class.getResourceAsStream("/lexicon/morphology.sha256")){
     if(hash==null||!java.util.HexFormat.of().formatHex(digest.digest()).equals(new String(hash.readAllBytes(),StandardCharsets.UTF_8).trim()))throw new IllegalStateException("Empreinte morphologique invalide");
    }
    try(var rawAnimate=LexiconMorphologicalEngine.class.getResourceAsStream("/lexicon/animate-nouns.tsv");var hash=LexiconMorphologicalEngine.class.getResourceAsStream("/lexicon/animate-nouns.tsv.sha256")){
     if(rawAnimate==null||hash==null)throw new IllegalStateException("Ressource animacité absente");
     byte[] bytes=rawAnimate.readAllBytes();String actual=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
     if(!actual.equals(new String(hash.readAllBytes(),StandardCharsets.UTF_8).trim()))throw new IllegalStateException("Empreinte animacité invalide");
     for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank()&&!line.startsWith("#"))animateNouns.add(line);
    }
    try(var possibleRaw=getClass().getResourceAsStream("/lexicon/possible-animate.tsv");var hash=getClass().getResourceAsStream("/lexicon/possible-animate.tsv.sha256")){if(possibleRaw==null||hash==null)throw new IllegalStateException("Ressource d’animacité possible absente");byte[] bytes=possibleRaw.readAllBytes();if(!java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).equals(new String(hash.readAllBytes(),StandardCharsets.UTF_8).trim()))throw new IllegalStateException("Empreinte d’animacité possible invalide");for(String line:new String(bytes,StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank()&&!line.startsWith("#"))possibleAnimate.add(line);}
   }catch(java.security.NoSuchAlgorithmException|IOException e){throw new IllegalStateException(e);}
  }
 }
 private static class Holder {static final Data DATA=new Data();}
 private final Data data=Holder.DATA;
 public static String norm(String s){return java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('’','\'');}
 public static String pos(String p){if(Set.of("NOUN","NC","N").contains(p))return "NOUN";if(p.startsWith("ADJ"))return "ADJ";if(p.equals("DET"))return "DET";return p;}
 public List<Form> readings(String surface,String p){return data.words.getOrDefault(norm(surface),List.of()).stream().filter(f->f.matches(pos(p))).toList();}
 public List<Form> paradigm(String lemma,String p){return data.lemmas.getOrDefault(norm(lemma),List.of()).stream().filter(f->f.matches(pos(p))).toList();}
 public boolean negative(String word){String s=norm(word);return data.negative.contains(s)||s.endsWith("'")&&data.negative.contains(s.substring(0,s.length()-1)+"e");}
 public boolean attestedIndirectVerb(String surface){return data.indirectVerbForms.contains(norm(surface));}
 public boolean possibleAnimateNoun(String lemma){return data.possibleAnimate.contains(norm(lemma));}
 public boolean attestedAnimateNoun(String lemma){return data.animateNouns.contains(norm(lemma));}
 public Optional<String> lemma(String surface,String p){var values=readings(surface,p).stream().map(Form::lemma).distinct().toList();return values.size()==1?Optional.of(values.get(0)):Optional.empty();}
 public MorphologicalFeatures analyze(TokenData token){
  var forms=readings(token.word(),token.pos());String lemma=lemma(token.word(),token.pos()).orElse("");
  if(lemma.isEmpty()&&(token.pos().startsWith("V")||token.pos().equals("AUX")||token.pos().equals("ADV"))){var ls=data.contextLemmas.getOrDefault(norm(token.word()),Set.of());if(ls.size()==1)lemma=ls.iterator().next();}
  var genders=forms.stream().flatMap(f->f.tags().stream()).filter(t->Set.of("mas","fem").contains(t)).distinct().toList();
  var numbers=forms.stream().flatMap(f->f.tags().stream()).filter(t->Set.of("sg","pl").contains(t)).distinct().toList();
  return new MorphologicalFeatures(lemma,pos(token.pos()),genders.size()==1?genders.get(0):"",numbers.size()==1?numbers.get(0):"");
 }
 public Optional<String> inflect(String lemma,MorphologicalFeatures target){
  if(!target.hasAgreementEvidence())return Optional.empty();
  var forms=paradigm(lemma,target.pos()).stream().filter(f->f.gender(target.gender())&&f.number(target.number())).map(Form::word).distinct().toList();
  return forms.size()==1?Optional.of(forms.get(0)):Optional.empty();
 }
 public static String caseLike(String source,String target){
  if(source.codePoints().filter(Character::isLetter).count()>1&&source.equals(source.toUpperCase(Locale.ROOT)))return target.toUpperCase(Locale.ROOT);
  return !source.isEmpty()&&Character.isUpperCase(source.codePointAt(0))?Character.toUpperCase(target.charAt(0))+target.substring(1):target;
 }
}
