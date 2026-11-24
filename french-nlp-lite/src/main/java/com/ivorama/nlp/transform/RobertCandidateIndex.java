package com.ivorama.nlp.transform;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
/** Diagnostic index: candidates are not approved substitutions. */
public final class RobertCandidateIndex {
 public record Relation(int page,String line,String head,String pos,String sense,String term,boolean corroborated,String raw) {}
 public record Match(Relation relation,boolean inverse) { public String candidate(){return inverse?relation.head():relation.term();} }
 private final Map<String,List<Match>> index;
 private final int count;
 private RobertCandidateIndex(Map<String,List<Match>> index,int count){this.index=Map.copyOf(index);this.count=count;}
 private static String norm(String s){return Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT),Normalizer.Form.NFC);}
 public static RobertCandidateIndex load() throws IOException {
  var in=RobertCandidateIndex.class.getResourceAsStream("/lexicon/robert/candidates-cleaned.tsv");
  if(in==null)throw new FileNotFoundException("lexicon/robert/candidates-cleaned.tsv");
  try(in){return read(in);}
 }
 public static RobertCandidateIndex read(InputStream in)throws IOException {
  Map<String,List<Match>> map=new HashMap<>();int n=0;
  try(var r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
   if(!"page\tline\thead\tpos\tsense\tterm\tposCorroborated\traw".equals(r.readLine()))throw new IOException("Invalid TSV header");
   String s;while((s=r.readLine())!=null){var f=s.split("\t",-1);if(f.length!=8||!Set.of("true","false").contains(f[6]))throw new IOException("Invalid row "+(n+2));
    Relation rel=new Relation(Integer.parseInt(f[0]),f[1],f[2],f[3],f[4],f[5],Boolean.parseBoolean(f[6]),f[7]);
    map.computeIfAbsent(norm(rel.head()),k->new ArrayList<>()).add(new Match(rel,false));
    map.computeIfAbsent(norm(rel.term()),k->new ArrayList<>()).add(new Match(rel,true));n++;
   }
  }map.replaceAll((k,v)->List.copyOf(v));return new RobertCandidateIndex(map,n);
 }
 public List<Match> lookup(String lemma){return index.getOrDefault(norm(lemma),List.of());}
 public int relationCount(){return count;}
 public int indexedWordCount(){return index.size();}
 public List<Relation> relations(){return index.values().stream().flatMap(List::stream).filter(m->!m.inverse()).map(Match::relation).distinct().toList();}
 public List<Relation> group(Relation relation){return lookup(relation.head()).stream().filter(m->!m.inverse()).map(Match::relation).filter(r->r.page()==relation.page()&&r.head().equals(relation.head())&&r.pos().equals(relation.pos())&&r.sense().equals(relation.sense())).toList();}
}
