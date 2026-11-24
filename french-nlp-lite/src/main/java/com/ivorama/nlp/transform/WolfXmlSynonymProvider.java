package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Lecteur SAX du format DebVisDic réel de WOLF 1.0b4, sans accès réseau. */
public final class WolfXmlSynonymProvider implements SynonymProvider {
  private record Lexeme(String word, String pos) {}
  private final Map<Lexeme,SortedSet<String>> index = new HashMap<>();
  private int synsetCount;
  private int indexedSynsetCount;
  public int synsetCount() { return synsetCount; }
  public int indexedSynsetCount() { return indexedSynsetCount; }
  public int indexedLemmaCount() { return index.size(); }

  public WolfXmlSynonymProvider(Path file) {
    this(open(file));
  }
  private static InputStream open(Path file) {
    try { return Files.newInputStream(file); }
    catch (Exception e) { throw new IllegalStateException("WOLF absent: " + file,e); }
  }
  public static WolfXmlSynonymProvider bundled() {
    InputStream in = WolfXmlSynonymProvider.class.getResourceAsStream("/lexicon/wolf.xml");
    if (in == null) throw new IllegalStateException("WOLF absent du classpath: /lexicon/wolf.xml");
    return new WolfXmlSynonymProvider(in);
  }
  private WolfXmlSynonymProvider(InputStream stream) {
    try (InputStream in=stream) {
      var f=SAXParserFactory.newInstance();
      f.setFeature("http://xml.org/sax/features/external-general-entities",false);
      f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
      f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
      f.setXIncludeAware(false);
      f.setNamespaceAware(false);
      f.newSAXParser().parse(in,new DefaultHandler() {
        private final StringBuilder content=new StringBuilder();
        private String currentElement="";
        private final List<String> literals=new ArrayList<>();
        private String pos="";
        private boolean inSynset=false;
        @Override public void startElement(String uri,String local,String q,Attributes a) {
          content.setLength(0); currentElement=q;
          if (q.equals("SYNSET")) { inSynset=true; literals.clear(); pos=""; synsetCount++; }
        }
        @Override public void characters(char[] ch,int start,int length) { if (currentElement.equals("LITERAL") || currentElement.equals("POS")) content.append(ch,start,length); }
        @Override public void endElement(String uri,String local,String q) {
          if (!inSynset) return;
          if (q.equals("LITERAL")) {
            String s=content.toString().trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !s.equals("_empty_")) literals.add(s);
          } else if (q.equals("POS")) pos=content.toString().trim();
          else if (q.equals("SYNSET")) {
            String normalized=normalizePos(pos);
            if (literals.size()>1 && !normalized.isBlank()) {
              indexedSynsetCount++;
              for (String word:literals) for (String candidate:literals) {
                if (!word.equals(candidate) && !candidate.contains(" ") && !candidate.contains("_"))
                  index.computeIfAbsent(new Lexeme(word,normalized),k->new TreeSet<>()).add(candidate);
              }
            }
            inSynset=false;
          }
          content.setLength(0);
        }
      });
    } catch (Exception e) { throw new IllegalStateException("Lecture du véritable WOLF impossible",e); }
  }
  private static String normalizePos(String raw) {
    String p=raw.toLowerCase(Locale.ROOT);
    if (p.equals("n") || p.equals("noun") || p.equals("nc") || p.equals("propn")) return "N";
    if (p.equals("a") || p.equals("s") || p.equals("adj") || p.equals("adjective")) return "A";
    if (p.equals("v") || p.equals("verb")) return "V";
    if (p.equals("r") || p.equals("adv") || p.equals("adverb")) return "R";
    return "";
  }
  /** Retourne TOUS les candidats de même catégorie WOLF, pas seulement le premier. */
  @Override public List<String> candidates(TokenData token, SynonymRegister register,
      com.ivorama.nlp.core.SentenceAnalysis context) {
    String word=token.word().trim().toLowerCase(Locale.ROOT);
    String pos=normalizePos(token.pos());
    return List.copyOf(index.getOrDefault(new Lexeme(word,pos),Collections.emptySortedSet()));
  }
  @Override public Optional<String> synonym(TokenData token,SynonymRegister register) {
    String word=token.word().trim().toLowerCase(Locale.ROOT);
    String pos=normalizePos(token.pos());
    return index.getOrDefault(new Lexeme(word,pos),Collections.emptySortedSet()).stream()
      .filter(candidate->!candidate.equals(word)).findFirst();
  }
}
