package com.ivorama.nlp;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Affichage des catégories et des dépendances, à partir de l’analyse normalisée. */
public final class ComplexSentenceAnalyzer {
    private ComplexSentenceAnalyzer() {}

    public static void main(String[] args) throws IOException {
        String texte = args.length == 0
                ? "Le chat que mon frère a adopté mange sa pâtée pendant que nous regardons un film."
                : String.join(" ", args);
        run(texte);
    }

    public static void run(String texte) throws IOException {
        var sentences = new FrenchNlpAnalyzer().analyze(texte);
        System.out.println("Texte : " + texte);
        for (var sentence : sentences) {
            System.out.println("\nMOTS ET CATÉGORIES :");
            for (var token : sentence.tokens()) {
                System.out.printf("%d. %s : %s [%s]%n", token.index(), token.word(),
                        categorie(token.pos()), token.pos());
            }
            System.out.println("\nRELATIONS SYNTAXIQUES :");
            var root = sentence.token(sentence.rootTokenIndex());
            System.out.printf("Racine : %s [%s]%n", root.word(), root.pos());
            analyserNoeud(sentence, root, 1, new HashSet<>());
            System.out.println("\nDÉPENDANCES BRUTES :");
            System.out.printf("root(ROOT-0, %s-%d)%n", root.word(), root.index());
            for (var dependent : sentence.tokens()) {
                if (dependent.headIndex() < 1) continue;
                var head = sentence.token(dependent.headIndex());
                System.out.printf("%s(%s-%d, %s-%d)%n", dependent.dependency(),
                        head.word(), head.index(), dependent.word(), dependent.index());
            }
        }
    }

    private static void analyserNoeud(SentenceAnalysis sentence, TokenData parent,
                                      int profondeur, Set<Integer> visites) {
        if (!visites.add(parent.index())) return;
        for (var enfant : sentence.dependentsOf(parent.index())) {
            String etiquette = enfant.dependency();
            System.out.printf("%s%s [%s] : %s → %s%n", "  ".repeat(profondeur),
                    relation(etiquette), etiquette, parent.word(), enfant.word());
            analyserNoeud(sentence, enfant, profondeur + 1, visites);
        }
    }

    private static String categorie(String etiquette) {
        return switch (etiquette) {
            case "NOUN" -> "Nom commun";
            case "PROPN" -> "Nom propre";
            case "VERB" -> "Verbe";
            case "AUX" -> "Auxiliaire";
            case "DET" -> "Déterminant";
            case "PRON" -> "Pronom";
            case "ADJ" -> "Adjectif";
            case "ADV" -> "Adverbe";
            case "ADP" -> "Adposition (préposition en français)";
            case "SCONJ" -> "Conjonction de subordination";
            case "CCONJ" -> "Conjonction de coordination";
            case "NUM" -> "Numéral";
            case "PART" -> "Particule";
            case "INTJ" -> "Interjection";
            case "PUNCT" -> "Ponctuation";
            case "SYM" -> "Symbole";
            default -> "Autre catégorie";
        };
    }

    private static String relation(String etiquette) {
        return switch (etiquette) {
            case "nsubj" -> "Sujet nominal";
            case "nsubj:pass" -> "Sujet nominal passif";
            case "csubj" -> "Sujet propositionnel";
            case "obj", "dobj" -> "Objet direct";
            case "iobj" -> "Objet indirect";
            case "obl" -> "Complément oblique";
            case "obl:arg" -> "Argument oblique";
            case "obl:mod" -> "Modifieur oblique";
            case "obl:tmod" -> "Modifieur temporel oblique";
            case "advcl" -> "Subordonnée adverbiale";
            case "acl" -> "Proposition modifiant un nom";
            case "acl:relcl" -> "Proposition relative";
            case "ccomp" -> "Complément propositionnel à sujet propre";
            case "xcomp" -> "Complément propositionnel sans sujet exprimé";
            case "det" -> "Déterminant";
            case "case" -> "Marqueur de cas (notamment préposition)";
            case "mark" -> "Marqueur de subordination";
            case "aux" -> "Auxiliaire";
            case "aux:pass" -> "Auxiliaire du passif";
            case "cop" -> "Copule";
            case "nmod" -> "Complément nominal";
            case "amod" -> "Modifieur adjectival";
            case "advmod" -> "Modifieur adverbial";
            case "conj" -> "Élément coordonné";
            case "cc" -> "Marqueur de coordination";
            case "fixed" -> "Élément d'une expression figée";
            case "punct" -> "Ponctuation";
            default -> "Relation non traduite";
        };
    }
}
