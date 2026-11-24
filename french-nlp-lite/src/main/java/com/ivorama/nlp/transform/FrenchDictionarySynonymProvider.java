package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Validated local TSV, not raw OCR. Each row describes one sense. */
public final class FrenchDictionarySynonymProvider implements SynonymProvider {
    private record Sense(String pos, String gender, SynonymRegister register, Set<String> words, Set<String> context) {}
    private final Map<String,List<Sense>> index = new HashMap<>();
    private static String norm(String s) { return java.text.Normalizer.normalize(s.strip().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFC); }
    private static Set<String> terms(String s) { return Arrays.stream(s.split("\\|",-1)).map(FrenchDictionarySynonymProvider::norm).filter(w->!w.isEmpty()).collect(Collectors.toCollection(TreeSet::new)); }
    public FrenchDictionarySynonymProvider(Path path) throws IOException {
        try(var lines=Files.lines(path)) {
            var it=lines.iterator(); int line=0;
            while(it.hasNext()) {
                String raw=it.next(); line++;
                if(raw.isBlank()||raw.startsWith("#")) continue;
                String[] a=raw.split("\t",-1);
                if(a.length!=7 || !Set.of("NOUN","ADJ","VERB","ADV").contains(a[1]) || !Set.of("M","F","X").contains(a[2]) || !a[6].matches("[1-9][0-9]*")) throw new IOException("Invalid reviewed TSV line "+line);
                var sense=new Sense(a[1],a[2],SynonymRegister.valueOf(a[3]),terms(a[4]),terms(a[5]));
                index.computeIfAbsent(norm(a[0]),k->new ArrayList<>()).add(sense);
            }
        }
        if(index.isEmpty()) throw new IOException("Empty reviewed dictionary");
    }
    private static String pos(TokenData t) {
        if(Set.of("PROPN","NPP").contains(t.pos())) return "";
        if(t.isNoun()) return "NOUN";
        if(t.isAdjective()) return "ADJ";
        return t.pos();
    }
    private List<Sense> senses(String word,String pos) { return index.getOrDefault(norm(word),List.of()).stream().filter(s->s.pos.equals(pos)).toList(); }
    private boolean valid(TokenData t,Sense s,String w,SynonymRegister r) {
        if(s.register!=r||!w.matches("[\\p{L}]+(?:[-'’][\\p{L}]+)*")||w.equalsIgnoreCase(t.word()))return false;
        if(!Set.of("NOUN","ADJ","ADV").contains(s.pos))return false;
        return senses(w,s.pos).stream().anyMatch(v->v.register==r);
    }
    private double proximity(String source, Sense chosen, String candidate) {
        Set<String> left = new HashSet<>(chosen.words);
        left.remove(candidate); left.remove(source);
        return senses(candidate, chosen.pos).stream()
            .filter(s -> s.words.contains(source))
            .mapToDouble(s -> {
                Set<String> right = new HashSet<>(s.words);
                right.remove(source); right.remove(candidate);
                return DictionarySemanticScorer.overlap(left, right);
            }).max().orElse(0);
    }
    @Override public boolean supportsRegisters(){return true;}
    @Override public Optional<String> synonym(TokenData t,SynonymRegister r){return synonym(t,r,null);}
    @Override public Optional<String> synonym(TokenData t,SynonymRegister r,SentenceAnalysis ctx) {
        var senses=senses(t.word(),pos(t)); if(senses.isEmpty()) return Optional.empty();
        var words=ctx==null?Set.<String>of():ctx.tokens().stream().filter(x->x.index()!=t.index()).map(x->norm(x.word())).collect(Collectors.toSet());
        Sense winner=null; long best=-1; boolean tie=false;
        for(var s:senses){long score=s.context.stream().filter(words::contains).count(); if(score>best){winner=s;best=score;tie=false;}else if(score==best)tie=true;}
        if(senses.size()>1 && (tie||best==0))return Optional.empty();
        final Sense chosen=winner;
        return chosen.words.stream().filter(w->valid(t,chosen,w,r)).filter(w->!words.contains(w)).sorted(Comparator.<String>comparingDouble(w -> proximity(norm(t.word()), chosen, w)).reversed().thenComparing(Comparator.naturalOrder())).findFirst().map(w->Character.isUpperCase(t.word().charAt(0))?Character.toUpperCase(w.charAt(0))+w.substring(1):w);
    }
    @Override public boolean isCuratedCandidate(TokenData t,SynonymRegister r,String w){return senses(t.word(),pos(t)).stream().anyMatch(s->s.words.contains(norm(w))&&valid(t,s,norm(w),r));}
    @Override public CandidateDecision assess(TokenData t,SynonymRegister r,String candidate,SentenceAnalysis context){return synonym(t,r,context).filter(c->c.equalsIgnoreCase(candidate)).isPresent()?new CandidateDecision(true,"REVIEWED_DICTIONARY_SELECTED_SENSE",1,"reviewed","Externally reviewed relation and register; inflection still checked centrally"):CandidateDecision.reject("REVIEWED_SENSE_NOT_SELECTED");}
    @Override public List<String> inventory(TokenData t,SynonymRegister r,SentenceAnalysis context){return senses(t.word(),pos(t)).stream().flatMap(s->s.words.stream()).distinct().sorted().toList();}
}
