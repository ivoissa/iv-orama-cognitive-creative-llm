package fr.ivorama.cognitive;
import java.util.*;

/** Paradigmes déclarés, indépendants des noms de domaines ; aucune vérité ni antonymie induite. */
public final class CopularPropertyLexicon {
 public record Entry(String concept,FrenchAgreement.Paradigm paradigm) {
  public Entry {StructuredLogic.identifier(concept);Objects.requireNonNull(paradigm);
   for(String form:List.of(paradigm.masculineSingular(),paradigm.feminineSingular(),paradigm.masculinePlural(),paradigm.femininePlural()))
    if(form==null||!form.matches("[\\p{L}][\\p{L}-]*"))throw new IllegalArgumentException("Forme lexicale invalide");}
 }
 private final List<Entry> entries;
 public CopularPropertyLexicon(Collection<Entry> entries){this.entries=List.copyOf(entries);if(this.entries.size()>256)throw new IllegalArgumentException("Plus de 256 paradigmes");}
 public CopularPropertyLexicon with(Entry entry){var copy=new ArrayList<>(entries);copy.add(entry);return new CopularPropertyLexicon(copy);}
 public Optional<String> resolve(String form,Optional<SemanticLexicon.Gender> gender){
  var candidates=entries.stream().filter(e->!e.paradigm().analyze(form).isEmpty()).toList();
  if(candidates.isEmpty())return Optional.empty();
  Set<String> concepts=new LinkedHashSet<>();candidates.forEach(e->concepts.add(e.concept()));
  if(concepts.size()!=1)throw new IllegalArgumentException("Sens lexical ambigu");
  var expected=new FrenchAgreement.Features(gender.map(g->g==SemanticLexicon.Gender.FEMININE?FrenchAgreement.Gender.FEMININE:FrenchAgreement.Gender.MASCULINE).orElse(FrenchAgreement.Gender.UNKNOWN),FrenchAgreement.GrammaticalNumber.SINGULAR);
  boolean compatible=candidates.stream().flatMap(e->e.paradigm().analyze(form).stream()).anyMatch(f->{try{expected.unify(f);return true;}catch(IllegalArgumentException ex){return false;}});
  if(!compatible)throw new IllegalArgumentException("Accord adjectival incompatible avec le référent singulier");
  return Optional.of(concepts.iterator().next());
 }
 /** Explicit paradigm projection, never suffix stripping or a statistical lemma. */
 public Optional<String> distributiveSingular(String plural,SemanticLexicon.Gender gender){
  return distributiveSingular(plural,gender,gender);
 }
 public Optional<String> distributiveSingular(String plural,SemanticLexicon.Gender sourceGender,SemanticLexicon.Gender gender){
  var forms=new LinkedHashSet<String>();
  for(var entry:entries){var p=entry.paradigm();
   if(plural.equals(sourceGender==SemanticLexicon.Gender.FEMININE?p.femininePlural():p.masculinePlural()))
    forms.add(gender==SemanticLexicon.Gender.FEMININE?p.feminineSingular():p.masculineSingular());
  }
  if(forms.size()!=1)return Optional.empty();
  String form=forms.iterator().next();resolve(form,Optional.of(gender));return Optional.of(form);
 }
 public static CopularPropertyLexicon defaults(){
  List<Entry> e=new ArrayList<>();
  for(var old:new SemanticLexicon().entries())e.add(new Entry(old.concept(),new FrenchAgreement.Paradigm(old.masculineSingular(),old.feminineSingular(),old.masculinePlural(),old.femininePlural())));
  // Vocabulaire morphologique réutilisable : concepts, jamais des faits ni des réponses.
  for(String base:List.of("allumé","éclairé","coloré","lavé","rangé","posé"))
   e.add(new Entry(java.text.Normalizer.normalize(base,java.text.Normalizer.Form.NFD).replaceAll("\\p{M}",""),FrenchAgreement.paradigm(base)));
  add(e,"stable","stable","stable","stables","stables");
  add(e,"chaud","chaud","chaude","chauds","chaudes");add(e,"froid","froid","froide","froids","froides");
  add(e,"eteint","éteint","éteinte","éteints","éteintes");add(e,"clair","clair","claire","clairs","claires");
  add(e,"bleu","bleu","bleue","bleus","bleues");add(e,"grand","grand","grande","grands","grandes");
  add(e,"petit","petit","petite","petits","petites");add(e,"leger","léger","légère","légers","légères");
  add(e,"lourd","lourd","lourde","lourds","lourdes");add(e,"sec","sec","sèche","secs","sèches");
  add(e,"doux","doux","douce","doux","douces");add(e,"gris","gris","grise","gris","grises");
  return new CopularPropertyLexicon(e);
 }
 private static void add(List<Entry> entries,String concept,String ms,String fs,String mp,String fp){entries.add(new Entry(concept,new FrenchAgreement.Paradigm(ms,fs,mp,fp)));}
}
