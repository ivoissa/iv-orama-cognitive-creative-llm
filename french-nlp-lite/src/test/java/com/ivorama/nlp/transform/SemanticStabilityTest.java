package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real resources and real CoreNLP, selected regression controls, not an independent benchmark. */
class SemanticStabilityTest {
 private static FrenchNlpAnalyzer analyzer;
 private static SynonymProvider provider;
 private static TransformationConfig config;
 @BeforeAll static void setup(){analyzer=new FrenchNlpAnalyzer();provider=SynonymProviderFactory.create(Path.of("src/test/resources/profiles/candidate.yml"));config=TransformationConfigLoader.load(Path.of("src/test/resources/profiles/candidate.yml"));}
 private String transform(String s){return new SentenceTransformerEngine(provider).transform(analyzer.analyzeFirstSentence(s),config).transformed();}
 @Test void physicianSenseIsResolvedBeforeCandidateSelection(){assertEquals("Le docteur examine le patient dans son cabinet.",transform("Le médecin examine le patient dans son cabinet."));}
 @Test void unrelatedHypernymReadingDoesNotVetoExactIdentifiedReading(){assertEquals("Le bateau transporte des marchandises vers le port.",transform("Le navire transporte des marchandises vers le port."));}
 @Test void ambiguousRiverCandidatesRemainUnchanged(){assertEquals("La rivière traverse la vallée.",transform("La rivière traverse la vallée."));}
 @Test void actualPartAndAntonymVetoesRemain(){var e=RobertGroupEvidence.load();var d=new RobertDisambiguator(e,loadIndex());assertFalse(d.assess("chaud","a","froid",List.of("température","chaleur")).allowed());assertFalse(d.assess("fenêtre","n","vitre",List.of("maison","lumière")).allowed());}
 @Test void contextualDecisionIsDeterministic(){String s="Le médecin examine le patient dans son cabinet.";String a=transform(s);for(int n=0;n<3;n++)assertEquals(a,transform(s));}
 @Test void grammaticalGenderChangeUsesRealProvider(){assertEquals("Le vélo possède deux roues et des pédales.",transform("La bicyclette possède deux roues et des pédales."));}
 @Test void disabledLexicalOptionsAreHonored(){var off=new TransformationConfig(false,true,false,false,false,false,false,false,42,false);String text="Le médecin examine le patient dans son cabinet.";assertEquals(text,new SentenceTransformerEngine(provider).transform(analyzer.analyzeFirstSentence(text),off).transformed());}
 @Test void sourceSenseStillRequiresPositiveContext(){var d=new RobertDisambiguator(RobertGroupEvidence.load(),loadIndex());assertFalse(d.select("médecin","n",List.of("xxinconnu")).status().equals("CANDIDATES"));}
 private static RobertCandidateIndex loadIndex(){try{return RobertCandidateIndex.load();}catch(Exception ex){throw new IllegalStateException(ex);}}
}
