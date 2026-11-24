package com.ivorama.nlp.transform;
public final class SynonymProviderFactory {
 private SynonymProviderFactory() {}
 public static SynonymProvider create(java.nio.file.Path yaml){
  var lexical=TransformationConfigLoader.loadLexical(yaml);
  // Addition needs lexical evidence even when synonym substitution is disabled.
  // TransformationConfig keeps noun/adjective substitution switches disabled.
  if(!lexical.enabled()&&TransformationConfigLoader.load(yaml).emphaseMode())
   lexical=new LexicalConfig(true,lexical.wolfEnabled(),lexical.libraryEnabled(),lexical.dictionaryPath(),lexical.cacheRead(),lexical.cacheWrite(),lexical.cachePath(),lexical.fallbackEnabled(),lexical.fallbackXmlPath(),lexical.robert(),lexical.disco());
  return create(lexical,TransformationConfigLoader.loadDecisionWeights(yaml));
 }
 public static SynonymProvider create(LexicalConfig config) {
  return create(config,TransformationConfigLoader.loadDecisionWeightsDefault());
 }
 public static SynonymProvider create(LexicalConfig config,DecisionWeights weights) {
  if (!config.enabled()) return (token, register) -> java.util.Optional.empty();
  if(config.disco().enabled()){
   if(!config.robert().enabled())throw new IllegalArgumentException("DISCO requires Robert candidate groups");
   return new DiscoGroupSynonymProvider(config.disco());
  }
  String reviewed = System.getProperty("ivorama.dictionary.reviewed");
  if (reviewed != null) {
   try { return new FrenchDictionarySynonymProvider(java.nio.file.Path.of(reviewed)); }
   catch (java.io.IOException e) { throw new IllegalStateException("Dictionnaire validé inaccessible", e); }
  }
  // In the conservative profile, legacy candidate enumerators cannot authorize
  // substitutions. Do not initialize duplicate OMW/WOLF indexes merely to reject them.
  if(config.robert().enabled()&&!config.robert().fallbackToOmw()&&!config.libraryEnabled()){
   try{SynonymProvider provider=new RobertContextProvider(new RobertDisambiguator(RobertGroupEvidence.load(),RobertCandidateIndex.load(),config.robert()),new com.ivorama.nlp.core.LexiconMorphologicalEngine());
    return new CachingSynonymProvider(provider,config.cachePath(),config.cacheRead(),config.cacheWrite());
   }catch(java.io.IOException e){throw new IllegalStateException("Robert inaccessible",e);}
  }
  OmwFrenchSynonymProvider omw = new OmwFrenchSynonymProvider();
  java.util.List<CombinedSynonymProvider.Source> sources = new java.util.ArrayList<>();
  sources.add(new CombinedSynonymProvider.Source("TSV",new ResourceSynonymProvider()));
  sources.add(new CombinedSynonymProvider.Source("OMW",omw));
  if (config.libraryEnabled())try{sources.add(new CombinedSynonymProvider.Source("WORDNET",new WordNetSynonymProvider(config.dictionaryPath())));}catch(IllegalStateException unavailable){if(!config.fallbackEnabled())throw unavailable;}
  if (config.wolfEnabled()) sources.add(new CombinedSynonymProvider.Source("WOLF",WolfXmlSynonymProvider.bundled()));
  var ranked = new CombinedSynonymProvider(sources,omw,weights);
  if(config.robert().enabled())try{ranked.withRobert(new RobertContextProvider(new RobertDisambiguator(RobertGroupEvidence.load(),RobertCandidateIndex.load(),config.robert()),new com.ivorama.nlp.core.LexiconMorphologicalEngine()));}catch(java.io.IOException e){throw new IllegalStateException("Robert inaccessible",e);}
  return new CachingSynonymProvider(ranked,config.cachePath(),config.cacheRead(),config.cacheWrite());
 }
}
