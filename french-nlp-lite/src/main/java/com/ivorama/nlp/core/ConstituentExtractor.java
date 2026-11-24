package com.ivorama.nlp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Classification des groupes à partir des dépendances normalisées. */
final class ConstituentExtractor {
    private final LexiconMorphologicalEngine morphology;

    ConstituentExtractor(LexiconMorphologicalEngine morphology) {
        this.morphology = morphology;
    }

    SentenceAnalysis extract(String text, List<TokenData> tokens, int rootIndex, int begin, int end) {
        var tree = new SentenceAnalysis(text, tokens, rootIndex, null, List.of(), List.of(), begin, end);
        TokenData root = tree.token(rootIndex);
        var rootEdges = tree.dependentsOf(rootIndex);
        Constituent subject = null;
        var objects = new ArrayList<Constituent>();
        var circumstantials = new ArrayList<Constituent>();
        for (var dependent : rootEdges) {
            String relation = dependent.dependency();
            String family = family(relation);
            var subtree = subtree(tree, dependent.index());
            switch (family) {
                case "nsubj", "csubj" -> {
                    if (subject == null) subject = Constituent.of(ConstituentRole.SUBJECT, relation, subtree);
                }
                case "obj", "dobj" -> objects.add(Constituent.of(ConstituentRole.DIRECT_OBJECT, relation, subtree));
                default -> {
                    if (family.equals("iobj") || relation.equals("obl:arg") || attestedDative(tree, root, dependent)) {
                        String label = family.equals("obl") ? "obl:arg:attested-dative" : relation;
                        objects.add(Constituent.of(ConstituentRole.INDIRECT_OBJECT, label, subtree));
                    } else if (Set.of("advmod", "advcl", "obl").contains(family)) {
                        circumstantials.add(Constituent.of(ConstituentRole.CIRCUMSTANTIAL, relation, subtree));
                    }
                }
            }
        }
        for (int i = 0; i < circumstantials.size(); i++) {
            var group = circumstantials.get(i);
            var last = tree.token(group.end());
            if (!last.isNoun() || morphology.attestedAnimateNoun(last.lemma())) continue;
            for (var dependent : rootEdges) {
                if (!family(dependent.dependency()).equals("ccomp")) continue;
                var tail = subtree(tree, dependent.index());
                if (tail.first() != group.end() + 1 || tail.size() != tail.last() - tail.first() + 1) continue;
                var marker = tree.token(tail.first()).word().toLowerCase(Locale.ROOT);
                if (!Set.of("que", "qui", "dont").contains(marker)) continue;
                var joined = new ArrayList<>(group.tokenIndices());
                joined.addAll(tail);
                circumstantials.set(i, Constituent.of(ConstituentRole.CIRCUMSTANTIAL,
                        "obl:nominal-relative-recovered", joined));
                break;
            }
        }
        return new SentenceAnalysis(text, tokens, rootIndex, subject, objects, circumstantials, begin, end);
    }

    private boolean attestedDative(SentenceAnalysis tree, TokenData root, TokenData noun) {
        return noun.dependency().equals("obl") && morphology.attestedIndirectVerb(root.word())
                && tree.dependentsOf(root.index()).stream().anyMatch(t -> Set.of("obj", "dobj").contains(family(t.dependency())))
                && noun.isNoun()
                && (morphology.attestedAnimateNoun(noun.lemma()) || morphology.possibleAnimateNoun(noun.lemma()))
                && tree.dependentsOf(noun.index()).stream()
                    .anyMatch(t -> family(t.dependency()).equals("case")
                        && Set.of("à", "au", "aux").contains(t.word().toLowerCase(Locale.ROOT)));
    }

    private static String family(String relation) {
        int colon = relation.indexOf(':');
        return colon < 0 ? relation : relation.substring(0, colon);
    }

    private static TreeSet<Integer> subtree(SentenceAnalysis tree, int root) {
        var indices = new TreeSet<Integer>();
        collect(tree, root, indices);
        return indices;
    }

    private static void collect(SentenceAnalysis tree, int index, Set<Integer> indices) {
        if (indices.add(index)) {
            for (var child : tree.dependentsOf(index)) collect(tree, child.index(), indices);
        }
    }
}
