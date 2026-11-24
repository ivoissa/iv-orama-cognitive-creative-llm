package fr.ivorama.cognitive;

import java.text.Normalizer;
import java.util.*;

/**
 * IVOC-LANG-010: provenance-preserving, explicitly lexicalized evidence extraction.
 * This is a bounded language fragment, NOT an open-world natural-language model.
 * Only whole clauses with an exact, user-declared positive/negative expression can
 * become premises. Unknown, modal or quoted clauses NEVER become observations.
 */
public final class NarrativeEvidenceLayer {
    public record Claim(String signal, boolean value, String clause) { }
    public record Report(String id, List<Claim> claims, List<String> unrecognized) {
        public Report { claims=List.copyOf(claims); unrecognized=List.copyOf(unrecognized); }
    }
    private record Expression(String signal, boolean value) { }
    private final HypothesisReasoningEngine engine;
    private final Map<String,Expression> vocabulary=new LinkedHashMap<>();
    private final Map<String,Report> reports=new LinkedHashMap<>();
    private final Map<String,Integer> activeAssertions=new HashMap<>();
    public NarrativeEvidenceLayer(HypothesisReasoningEngine engine) { this.engine=Objects.requireNonNull(engine); }

    /** Lexical binding with explicit truth polarity; never create a fact on registration. */
    public void register(String signal, String positive, String negative) {
        signal=atom(signal);
        String p=canonical(positive), n=canonical(negative);
        if(p.isBlank() || n.isBlank() || p.equals(n) || p.contains(";") || n.contains(";") ||
                p.contains(".") || n.contains(".") || p.contains("?") || n.contains("?") ||
                p.length()>160 || n.length()>160)
            throw new IllegalArgumentException("Deux expressions distinctes, sans ponctuation séparatrice, sont nécessaires");
        if(vocabulary.containsKey(p) || vocabulary.containsKey(n))
            throw new IllegalArgumentException("Expression déjà liée à un signal");
        if(vocabulary.size()+2>160) throw new IllegalStateException("Capacité du vocabulaire atteinte");
        vocabulary.put(p,new Expression(signal,true));
        vocabulary.put(n,new Expression(signal,false));
    }
    /** Full clause matching, no substring matches, no proof from a quotation or modal expression. */
    public Report ingest(String id,String narrative) {
        id=atom(id);
        if(reports.containsKey(id)) throw new IllegalArgumentException("Rapport déjà présent : "+id);
        if(narrative==null || narrative.isBlank() || narrative.length()>4000)
            throw new IllegalArgumentException("Texte du rapport vide ou trop long (maximum 4000 caractères)");
        var statements=new ArrayList<Claim>();
        var unknown=new ArrayList<String>();
        String[] clauses=narrative.split("[.;\\n]+",-1);
        if(clauses.length>40) throw new IllegalArgumentException("Trop de propositions (maximum 40)");
        for(String c:clauses) {
            String raw=c.trim(); if(raw.isEmpty()) continue;
            String clean=canonical(raw);
            // A question or a marked possibility never asserts a fact.
            if(uncertain(clean)) { unknown.add(raw+" [modalité/incertitude]"); continue; }
            // User-supplied lexicon makes the epistemic scope explicit and testable.
            Expression expression=vocabulary.get(clean);
            if(expression==null) { unknown.add(raw); continue; }
            statements.add(new Claim(expression.signal,expression.value,raw));
        }
        if(statements.isEmpty()) return new Report(id,List.of(),unknown);
        // Prevent hidden partial updates: validate all duplicates and observation caps first.
        var local=new HashSet<String>();
        for(Claim claim:statements) {
            String key=key(claim.signal,claim.value);
            if(!local.add(key)) throw new IllegalArgumentException("Assertion répétée dans le rapport : "+key);
            if(activeAssertions.containsKey(key))
                throw new IllegalArgumentException("Assertion déjà active dans un autre rapport : "+key);
        }
        if(engine.observations().size()+statements.size()>32)
            throw new IllegalArgumentException("Limite d'observations atteinte");
        // Commit atomically with reversible cleanup for unexpected runtime errors.
        var added=new ArrayList<Claim>();
        try {
            for(Claim claim:statements) {
                engine.observe(claim.signal,claim.value,"rapport "+id+" : "+claim.clause+" (utilisateur, non authentifié)");
                added.add(claim);
                activeAssertions.put(key(claim.signal,claim.value),1);
            }
        } catch(RuntimeException e) {
            for(Claim claim:added) {
                engine.withdraw(claim.signal,claim.value);
                activeAssertions.remove(key(claim.signal,claim.value));
            }
            throw e;
        }
        var report=new Report(id,statements,unknown);
        reports.put(id,report);
        return report;
    }
    /** Source-level retraction recomputes the same FCI question in the underlying engine. */
    public Report withdraw(String id) {
        id=atom(id);
        var r=reports.remove(id);
        if(r==null) throw new IllegalArgumentException("Rapport inconnu : "+id);
        for(Claim c:r.claims()) {
            engine.withdraw(c.signal,c.value);
            activeAssertions.remove(key(c.signal,c.value));
        }
        return r;
    }
    public List<String> reportIds() { return List.copyOf(reports.keySet()); }
    public int vocabularySize() { return vocabulary.size()/2; }
    public int reportCount() { return reports.size(); }
    private static boolean uncertain(String text) {
        return text.contains("?") || text.contains("\"") || text.contains("«") || text.contains("»") ||
                text.matches(".*\\b(peut.etre|probablement|semblerait|il semble|aurait|pourrait|hypothese|citation|document|documentation)\\b.*");
    }
    private static String key(String s,boolean b) { return s+"="+(b?"oui":"non"); }
    private static String atom(String raw) {
        if(raw==null || !raw.matches("[a-z][a-z0-9_]{0,39}"))
            throw new IllegalArgumentException("Identifiant : [a-z][a-z0-9_]{0,39}");
        return raw;
    }
    private static String canonical(String raw) {
        if(raw==null) return "";
        return Normalizer.normalize(raw.strip().toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").replaceAll("\\s+"," ");
    }
}
