package fr.ivorama.cognitive.generation;

import fr.ivorama.cognitive.DirectiveActs;
import fr.ivorama.cognitive.StructuredJsonWriter;
import java.util.*;

/** Source-backed task metadata. Recognition is partial and never certifies understanding. */
public record RequestContract(String task, boolean mixed, List<Span> evidence,
                              OptionalInt sentences, List<String> forbidden, List<String> required) {
    public record Span(String role, int start, int end, String text) {
        public Span { Objects.requireNonNull(role); Objects.requireNonNull(text); }
        Map<String,Object> value() { return Map.of("role",role,"start",start,"end",end,"text",text); }
    }
    public RequestContract {
        evidence=List.copyOf(evidence); forbidden=List.copyOf(forbidden); required=List.copyOf(required);
        Objects.requireNonNull(task); Objects.requireNonNull(sentences);
    }
    /** Literal rules are supplied by the existing OutputVerifier, not extracted a second time. */
    public static RequestContract capture(String input, OptionalInt sentences, List<String> forbidden,
                                          List<String> required, Optional<String> analysisSource) {
        Objects.requireNonNull(input);
        var act=DirectiveActs.classify(input);
        String task=analysisSource.isPresent()?"ANALYSIS":act.map(a->switch(a.kind()) {
            case CREATE -> "CREATION"; case ANALYZE -> "ANALYSIS";
            case EXPLAIN -> "EXPLANATION"; case REFORMULATE -> "REFORMULATION";
        }).orElse("UNKNOWN");
        boolean mixed=act.map(DirectiveActs.Directive::mixed).orElse(false);
        var spans=new ArrayList<Span>();
        act.ifPresent(a->{
            // Oversized/incomplete classification is not sufficient to bypass the planner.
            if(a.end()-a.start()<=800 && a.unresolved().isEmpty())
                spans.add(new Span("DIRECTIVE",a.start(),a.end(),input.substring(a.start(),a.end())));
        });
        analysisSource.filter(s->s.length()<=800).ifPresent(s->addSpan(input,spans,"TEXT_TO_ANALYZE",s));
        for(String term:forbidden) addSpan(input,spans,"FORBIDDEN_LITERAL",term);
        for(String term:required) addSpan(input,spans,"REQUIRED_LITERAL",term);
        for(Span span:spans)
            if(span.start()<0 || span.end()>input.length() || !input.substring(span.start(),span.end()).equals(span.text()))
                throw new IllegalArgumentException("Contrat sans ancrage exact");
        return new RequestContract(task,mixed,spans,sentences,forbidden,required);
    }
    private static void addSpan(String input,List<Span> spans,String role,String text) {
        int start=input.indexOf(text);
        if(start<0) throw new IllegalArgumentException("Contrainte absente de la demande");
        spans.add(new Span(role,start,start+text.length(),text));
    }
    public boolean simpleRecognizedTask() {
        return !mixed && !task.equals("UNKNOWN") && evidence.stream().anyMatch(s->s.role().equals("DIRECTIVE"));
    }
    public String json() {
        var value=new LinkedHashMap<String,Object>();
        value.put("kind","PARTIAL_REQUEST_CONTRACT"); value.put("task",task); value.put("mixed",mixed);
        value.put("evidence",evidence.stream().map(Span::value).toList());
        value.put("sentences",sentences.isPresent()?sentences.getAsInt():"UNSPECIFIED");
        value.put("forbidden",forbidden); value.put("required",required);
        value.put("semanticStatus","NOT_CERTIFIED"); return StructuredJsonWriter.json(value);
    }
}
