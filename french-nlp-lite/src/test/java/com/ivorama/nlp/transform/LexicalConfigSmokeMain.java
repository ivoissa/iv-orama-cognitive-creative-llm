package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import java.nio.file.*;
import java.util.*;
public final class LexicalConfigSmokeMain {
 public static void main(String[] args) throws Exception {
  LexicalConfig c = TransformationConfigLoader.loadLexicalDefault();
  if(c.libraryEnabled() || c.cacheWrite()) throw new AssertionError("defaults");
  Path config=Files.createTempFile("lexical", ".yml");
  Files.writeString(config,"synonyms:\n  jwi:\n    enabled: true\n  cache:\n    read_enabled: true\n    write_enabled: false\n");
  LexicalConfig parsed=TransformationConfigLoader.loadLexical(config);
  if(!parsed.libraryEnabled() || !parsed.cacheRead() || parsed.cacheWrite()) throw new AssertionError("yaml");
  Path cache=Files.createTempFile("synonym-cache", ".tsv"); Files.delete(cache);
  SynonymProvider source=(token,register)->Optional.of(register==SynonymRegister.COURANT?"félin":"minou");
  TokenData token=new TokenData(1,"chat","NOUN");
  var provider=new CachingSynonymProvider(source,cache,true,true);
  if(!provider.synonym(token,SynonymRegister.COURANT).orElseThrow().equals("félin")) throw new AssertionError("current");
  if(!provider.synonym(token,SynonymRegister.FAMILIER).orElseThrow().equals("minou")) throw new AssertionError("familiar");
  SynonymProvider absent=(tok,reg)->Optional.empty();
  var reader=new CachingSynonymProvider(absent,cache,true,false);
  if(!reader.synonym(token,SynonymRegister.COURANT).orElseThrow().equals("félin")) throw new AssertionError("cache current");
  if(!reader.synonym(token,SynonymRegister.FAMILIER).orElseThrow().equals("minou")) throw new AssertionError("cache familiar");
  Files.deleteIfExists(cache); Files.deleteIfExists(config);
  System.out.println("LEXICAL CONFIG/CACHE: 5/5 OK");
 }
}
