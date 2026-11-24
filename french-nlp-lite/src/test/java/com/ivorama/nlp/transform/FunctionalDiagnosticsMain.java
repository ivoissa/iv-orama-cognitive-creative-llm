package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.util.List;

/** Tests sur les sorties reelles du moteur, avec constituants injectes (sans CoreNLP). */
public final class FunctionalDiagnosticsMain {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        SynonymProvider provider = new ResourceSynonymProvider();
        SentenceTransformerEngine engine = new SentenceTransformerEngine(provider);
        TransformationConfig synonymsOnly = new TransformationConfig(false,true,false,false,false,false,false,false,1L);
        SentenceAnalysis bare = new SentenceAnalysis("Chat dort.",
                List.of(new TokenData(1,"Chat","NOUN"), new TokenData(2,"dort","VERB"), new TokenData(3,".","PUNCT")),
                2, new Constituent(ConstituentRole.SUBJECT,List.of(1),"nsubj"), List.of(), List.of());
        TransformationResult lexical = engine.transform(bare, synonymsOnly);
        System.out.println("NOM SANS DETERMINANT: " + lexical.transformed() + " / " + lexical.replacements());
        check(lexical.transformed().contains("Félin"), "Le synonyme du lexique doit etre utilise quand le contexte le permet");
        SentenceAnalysis withDet = TestFixtures.yodaSimple();
        TransformationResult guarded = new SentenceTransformerEngine(provider,new ConservativeMorphologicalEngine()).transform(withDet, synonymsOnly);
        System.out.println("NOM AVEC DETERMINANT: " + guarded.transformed() + " / " + guarded.replacements());
        check(guarded.replacements().isEmpty(), "Le moteur doit s'abstenir sans donnees de genre");
        TransformationConfig yoda = new TransformationConfig(false,true,false,true,false,false,false,false,1L);
        TransformationResult moved = new SentenceTransformerEngine((t,r)->java.util.Optional.empty()).transform(withDet, yoda);
        System.out.println("YODA: " + moved.transformed());
        check(moved.transformed().toLowerCase(java.util.Locale.ROOT).contains("la pâtée"), "COD perdu");
        check(moved.transformed().toLowerCase(java.util.Locale.ROOT).indexOf("la pâtée") < moved.transformed().toLowerCase(java.util.Locale.ROOT).indexOf("le chat"), "COD non deplace");
        check(!moved.transformed().contains(";."), "Ponctuation invalide");
        System.out.println("CONTROLES FONCTIONNELS REUSSIS: " + checks);
    }
}
