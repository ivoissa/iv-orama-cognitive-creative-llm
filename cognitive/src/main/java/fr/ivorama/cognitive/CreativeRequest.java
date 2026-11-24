package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;

/** Analyse bornée de la demande : aucun suffixe non reconnu n'est exécuté implicitement. */
public record CreativeRequest(String source,String form,CreativeTextEngine.Style style,List<String> themes,
                             Optional<Integer> lines,boolean rhyme,List<String> unresolved) {
 public CreativeRequest{themes=List.copyOf(themes);unresolved=List.copyOf(unresolved);Objects.requireNonNull(lines);}
 private static final int FLAGS=Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE;
 private static final Map<String,Integer> NUMBERS=Map.of("un",1,"une",1,"deux",2,"trois",3,"quatre",4,"cinq",5,"six",6,"sept",7,"huit",8,"neuf",9);
 public static CreativeRequest parse(String source){
  if(source==null||source.length()>8000)throw new IllegalArgumentException("Demande créative hors budget");
  String s=source.toLowerCase(Locale.ROOT).replace('’','\'');boolean[] used=new boolean[s.length()];List<String> unknown=new ArrayList<>(),themes=new ArrayList<>();
  Matcher head=Pattern.compile("^\\s*(un|une)\\s+((?:[\\p{L}-]+\\s+){0,3}?)(phrase|texte|poème|histoire|récit)\\b(?:\\s+(poétique|simple|court))?",FLAGS).matcher(s);
  if(!head.find())return new CreativeRequest(source,"UNKNOWN",CreativeTextEngine.Style.PLAIN,List.of(),Optional.empty(),false,List.of("Format de création non reconnu : phrase, texte, poème, histoire ou récit attendus"));
  mark(used,head.start(),head.end());
  if(!head.group(2).isBlank())unknown.add("Modificateur non pris en charge : « "+head.group(2).strip()+" »");
  String form=switch(head.group(3)){case "phrase"->"SENTENCE";case "poème"->"POEM";case "histoire","récit"->"STORY";default->"TEXT";};
  String article=Set.of("phrase","histoire").contains(head.group(3))?"une":"un";
  if(!head.group(1).equals(article))unknown.add("Déterminant incompatible avec le format « "+head.group(3)+" » : « "+article+" » attendu");
  var style="simple".equals(head.group(4))?CreativeTextEngine.Style.PLAIN:(form.equals("POEM")||"poétique".equals(head.group(4)))?CreativeTextEngine.Style.POETIC:CreativeTextEngine.Style.PLAIN;
  Matcher count=Pattern.compile("\\ben\\s+(\\d+|[\\p{L}]+)\\s+(vers|lignes?)\\b",FLAGS).matcher(s);Integer lines=null;
  while(count.find()){mark(used,count.start(),count.end());Integer n=number(count.group(1));if(lines!=null||n==null||n<1||n>4)unknown.add("Nombre de lignes absent, répété ou hors capacité 1..4 : "+count.group());else lines=n;}
  Matcher rhyme=Pattern.compile("\\b(?:rimés?|rimées?|avec des rimes|sans rimes)\\b",FLAGS).matcher(s);boolean requested=false;
  while(rhyme.find()){mark(used,rhyme.start(),rhyme.end());requested=true;}
  // Des emplacements nominaux distincts ; on s'arrête avant une autre construction de contrainte.
  Matcher topic=Pattern.compile("\\b(?:sur|qui parle de|parlant de)\\s+(.+?)(?=\\s+(?:qui parle de|parlant de|en|avec|sans|de)\\b|[,;]|$)",FLAGS).matcher(s);
  while(topic.find()){
   boolean overlap=false;for(int i=topic.start();i<topic.end();i++)overlap|=used[i];if(overlap)continue;
   String term=topic.group(1).strip().replaceFirst("^(?:(?:le|la|les|un|une)\\s+|l')","");
   if(term.isBlank())unknown.add(topic.group());else for(String item:term.split("\\s+et\\s+")){
    String value=item.strip().replaceFirst("^(?:(?:le|la|les|un|une)\\s+|l')","");
    if(value.isBlank())unknown.add("Thème manquant dans « "+topic.group()+" »");else themes.add(value);
   }mark(used,topic.start(),topic.end());
  }
  int begin=-1;for(int i=0;i<=s.length();i++){
   boolean boundary=i==s.length()||used[i]||",;".indexOf(s.charAt(i))>=0;
   if(!boundary&&begin<0)begin=i;
   if(boundary&&begin>=0){String fragment=source.substring(begin,i).strip();if(!fragment.isEmpty())unknown.add(fragment);begin=-1;}
  }
  return new CreativeRequest(source,form,style,themes,Optional.ofNullable(lines),requested,unknown);
 }
 private static Integer number(String s){try{return s.chars().allMatch(Character::isDigit)?Integer.valueOf(s):NUMBERS.get(s);}catch(NumberFormatException ex){return null;}}
 private static void mark(boolean[] used,int a,int b){Arrays.fill(used,a,b,true);}
 public List<String> unsupported(){List<String> out=new ArrayList<>(unresolved);if(form.equals("STORY"))out.add("Format histoire/récit reconnu, génération narrative non prise en charge");if(rhyme)out.add("Contrainte de rime reconnue mais non prise en charge");if(themes.size()>1)out.add("Plusieurs thèmes reconnus ; leur combinaison sémantique n'est pas prise en charge");if(form.equals("SENTENCE")&&lines.orElse(1)!=1)out.add("Une phrase ne peut pas être traitée comme plusieurs vers dans ce générateur");return List.copyOf(out);}
 public int clauseCount(){return lines.orElse(form.equals("SENTENCE")?1:2);}
 public String json(){return StructuredJsonWriter.json(Map.of("schema","ivoc.creative-request.1","source",source,"form",form,"style",style.name(),"themes",themes,"lines",lines.orElse(0),"rhymeRequested",rhyme,"unresolved",unresolved,"unsupported",unsupported()));}
 /** Relecture avec contrôle de cohérence source/champs ; aucun champ JSON falsifié n'est accepté. */
 public static CreativeRequest read(String json){
  if(json==null||json.length()>20000)throw new IllegalArgumentException("JSON créatif hors budget");
  Object raw=MiniJson.parse(json);if(!(raw instanceof Map<?,?> m)||!(m.get("source") instanceof String s))throw new IllegalArgumentException("Requête créative invalide");
  var expected=parse(s);if(!MiniJson.parse(expected.json()).equals(raw))throw new IllegalArgumentException("Requête créative incohérente avec sa source");return expected;
 }
}
