package com.ivorama.nlp.transform;
import java.nio.file.Path;
public record LexicalConfig(boolean enabled, boolean wolfEnabled, boolean libraryEnabled,Path dictionaryPath,boolean cacheRead,
 boolean cacheWrite,Path cachePath,boolean fallbackEnabled,Path fallbackXmlPath,RobertSettings robert,DiscoSettings disco) {
 public LexicalConfig(boolean e,boolean w,boolean j,Path dict,boolean r,boolean wr,Path cache,boolean fb,Path xml,RobertSettings robert){this(e,w,j,dict,r,wr,cache,fb,xml,robert,DiscoSettings.defaults());}
 public LexicalConfig(boolean e,boolean w,boolean j,Path dict,boolean r,boolean wr,Path cache,boolean fb,Path xml){this(e,w,j,dict,r,wr,cache,fb,xml,RobertSettings.defaults());}
 public LexicalConfig(boolean libraryEnabled, Path dictionaryPath, boolean cacheRead, boolean cacheWrite,
                      Path cachePath, boolean fallbackEnabled, Path fallbackXmlPath) {
  this(true,true,libraryEnabled,dictionaryPath,cacheRead,cacheWrite,cachePath,fallbackEnabled,fallbackXmlPath);
 }
 public static LexicalConfig defaults() {
  return new LexicalConfig(true,true,false,Path.of("data/wordnet-fr/dict"),false,false,
   Path.of("data/synonyms-cache.tsv"),false,Path.of("data/wolf.xml"));
 }
}
