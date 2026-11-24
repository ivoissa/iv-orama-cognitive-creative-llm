package com.ivorama.nlp.core;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.util.CoreMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Adaptateur unique vers Stanford CoreNLP. Le moteur de transformation ne dépend pas de CoreNLP.
 */
public final class FrenchNlpAnalyzer {
    private final StanfordCoreNLP pipeline;
    private final LexiconMorphologicalEngine morphology=new LexiconMorphologicalEngine();

    private final ConstituentExtractor extractor = new ConstituentExtractor(morphology);

    public FrenchNlpAnalyzer() {
        this.pipeline = new StanfordCoreNLP(loadFrenchProperties());
    }

    private static Properties loadFrenchProperties() {
        Properties props = new Properties();
        try (InputStream in = FrenchNlpAnalyzer.class.getClassLoader()
                .getResourceAsStream("StanfordCoreNLP-french.properties")) {
            if (in == null) {
                throw new IllegalStateException("Modèles français CoreNLP absents du classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger la configuration française CoreNLP", e);
        }
        props.setProperty("annotators", "tokenize,ssplit,mwt,pos,depparse");
        return props;
    }

    public SentenceAnalysis analyzeFirstSentence(String text) {
        var all=analyze(text);if(all.isEmpty())throw new IllegalStateException("Aucune phrase détectée");return all.get(0);
    }

    public List<SentenceAnalysis> analyze(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Le texte est vide");
        var result=new ArrayList<SentenceAnalysis>();int cursor=0;
        var boundaries=java.util.regex.Pattern.compile("(?:…|\\.{3})\\s+(?=\\p{Lu})").matcher(text);
        while(boundaries.find()){
            String before=text.substring(0,boundaries.end());
            long open=before.chars().filter(c->c=='«'||c=='“'||c=='(').count();
            long close=before.chars().filter(c->c=='»'||c=='”'||c==')').count();
            if(open!=close||before.chars().filter(c->c=='\"').count()%2!=0)continue;
            var following=analyzeRange(text.substring(boundaries.end()),boundaries.end());
            if(following.isEmpty()||!Set.of("VERB","AUX","V").contains(following.get(0).token(following.get(0).rootTokenIndex()).pos()))continue;
            result.addAll(analyzeRange(text.substring(cursor,boundaries.end()),cursor));cursor=boundaries.end();
        }
        result.addAll(analyzeRange(text.substring(cursor),cursor));return List.copyOf(result);
    }

    private List<SentenceAnalysis> analyzeRange(String text,int base) {
        if(text.isBlank())return List.of();
        Annotation document = new Annotation(text);
        pipeline.annotate(document);
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
        if (sentences == null) return List.of();
        List<SentenceAnalysis> result = new ArrayList<>();
        for (CoreMap sentence : sentences){
            int begin=sentence.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class),end=sentence.get(CoreAnnotations.CharacterOffsetEndAnnotation.class);
            var a=toAnalysis(text.substring(begin,end),sentence);
            result.add(new SentenceAnalysis(a.originalText(),a.tokens(),a.rootTokenIndex(),a.subject(),a.objects(),a.circumstantials(),base+begin,base+end));
        }
        return List.copyOf(result);
    }

    private SentenceAnalysis toAnalysis(String originalText, CoreMap sentence) {
        SemanticGraph graph = sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
        int sentenceBegin=sentence.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class);
        List<TokenData> tokens = new ArrayList<>();
        for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
            var basic=new TokenData(token.index(),token.word(),token.tag());
            var features=morphology.analyze(basic);
            var vertex=graph==null?null:graph.getNodeByIndexSafe(token.index());
            var incoming=vertex==null?List.<SemanticGraphEdge>of():graph.incomingEdgeList(vertex);
            int head=incoming.isEmpty()?0:incoming.get(0).getGovernor().index();
            String relation=incoming.isEmpty()?"root":incoming.get(0).getRelation().toString();
            tokens.add(new TokenData(token.index(), token.word(), token.tag(),token.beginPosition()-sentenceBegin,token.endPosition()-sentenceBegin,
                    features.lemma(),features.gender(),features.number(),head,relation));
        }

        if (graph == null || graph.getRoots().isEmpty()) {
            throw new IllegalStateException("Aucun graphe de dépendances disponible");
        }
        IndexedWord root = graph.getRoots().stream().min(Comparator.comparingInt(IndexedWord::index)).orElseThrow();

        return extractor.extract(originalText, tokens, root.index(), sentenceBegin,
                sentence.get(CoreAnnotations.CharacterOffsetEndAnnotation.class));
    }
}
