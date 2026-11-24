package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/**
 * Bounded French interface to composed ground logical proofs. Lexical clauses
 * must be explicitly registered; no open-language comprehension is claimed.
 */
public final class CompositionalFrenchDialogue {
    public record Intake(int accepted, List<String> ignored) {
        public Intake {ignored=List.copyOf(ignored);}
    }
    private final CompositionalReasoningEngine engine=new CompositionalReasoningEngine();
    private final Map<String,CompositionalReasoningEngine.Literal> vocabulary=new LinkedHashMap<>();
    private final Map<String,List<String>> reportFacts=new LinkedHashMap<>();
    private final Set<String> ruleIds=new LinkedHashSet<>();
    public CompositionalReasoningEngine engine() {return engine;}
    public void expression(String atom,String affirmative,String negative) {
        var pos=new CompositionalReasoningEngine.Literal(atom,true);
        String p=canonical(affirmative),n=canonical(negative);
        if(p.isBlank()||n.isBlank()||p.equals(n)|| p.length()>160||n.length()>160||
                p.matches(".*[.;?!\"«»].*")||n.matches(".*[.;?!\"«»].*")||
                vocabulary.containsKey(p)||vocabulary.containsKey(n))
            throw new IllegalArgumentException("Expressions distinctes non ambiguës obligatoires");
        if(vocabulary.size()>=48) throw new IllegalStateException("Lexique saturé");
        vocabulary.put(p,pos);vocabulary.put(n,pos.opposite());
    }
    public Intake report(String id,String narrative) {
        id=id(id);
        if(reportFacts.containsKey(id)) throw new IllegalArgumentException("Rapport déjà enregistré");
        if(narrative==null||narrative.isBlank()||narrative.length()>4000)
            throw new IllegalArgumentException("Rapport vide ou trop long");
        List<String> unknown=new ArrayList<>();
        List<CompositionalReasoningEngine.Literal> facts=new ArrayList<>();
        List<String> clauses=new ArrayList<>();
        String[] chunks=narrative.split("[.;\n]+",-1);
        if(chunks.length>32) throw new IllegalArgumentException("Trop de propositions");
        for(String chunk:chunks) {
            if(chunk.isBlank()) continue;
            String original=chunk.strip();
            var parsed=uncertain(original)?null:vocabulary.get(canonical(original));
            if(parsed==null) unknown.add(original);
            else {facts.add(parsed);clauses.add(original);}
        }
        var identifiers=new ArrayList<String>();
        try {
            for(int i=0;i<facts.size();i++) {
                String factId=id+"_"+i;
                engine.fact(factId,facts.get(i),"rapport "+id+" : "+clauses.get(i)+" (non authentifié)");
                identifiers.add(factId);
            }
        } catch(RuntimeException ex) {
            for(String factId:identifiers) engine.withdraw(factId);
            throw ex;
        }
        reportFacts.put(id,List.copyOf(identifiers));
        return new Intake(facts.size(),unknown);
    }
    public void rule(String id,String sentence) {
        id=id(id);
        if(ruleIds.contains(id))throw new IllegalArgumentException("Règle déjà enregistrée");
        String line=canonical(sentence);
        if(uncertain(line)||!line.startsWith("si ")||!line.contains(", alors ")||line.length()>500)
            throw new IllegalArgumentException("Règle attendue : si ..., alors ...");
        int then=line.lastIndexOf(", alors ");
        if(then<3)throw new IllegalArgumentException("Règle non reconnue");
        String left=line.substring(3,then).strip(),right=line.substring(then+8).strip();
        var consequence=vocabulary.get(right);
        if(consequence==null)throw new IllegalArgumentException("Conclusion lexicale non déclarée : "+right);
        List<List<CompositionalReasoningEngine.Literal>> possible=new ArrayList<>();
        splitAntecedents(left,new ArrayList<>(),possible);
        if(possible.size()!=1) throw new IllegalArgumentException("Conditions absentes ou ambiguës : "+left);
        engine.rule(id,possible.get(0),consequence,"règle contrôlée : "+sentence);
        ruleIds.add(id);
    }
    private void splitAntecedents(String remaining,List<CompositionalReasoningEngine.Literal> soFar,
                                  List<List<CompositionalReasoningEngine.Literal>> found) {
        if(found.size()>1||soFar.size()>=4)return;
        for(var entry:vocabulary.entrySet()) {
            String phrase=entry.getKey();
            if(!remaining.startsWith(phrase))continue;
            var next=new ArrayList<>(soFar);next.add(entry.getValue());
            if(remaining.length()==phrase.length()) found.add(next);
            else if(remaining.startsWith(" et ",phrase.length()))
                splitAntecedents(remaining.substring(phrase.length()+4),next,found);
        }
    }
    public String ask(String sentence) {
        String c=canonical(sentence).replaceAll("[?]$","").strip();
        if(c.startsWith("est-ce que ")) return answer(c.substring(11));
        if(c.startsWith("est ce que ")) return answer(c.substring(10));
        throw new IllegalArgumentException("Question attendue : Est-ce que ... ?");
    }
    public String question(String question) {
        String c=canonical(question);
        c=c.replaceAll("[?]$","").strip();
        if(c.startsWith("est-ce que ")) c=c.substring(11);
        else if(c.startsWith("est ce que ")) c=c.substring(10);
        return answer(c);
    }
    private String answer(String clause) {
        var goal=vocabulary.get(clause);
        if(goal==null) return "REPONSE : NON INTERPRÉTÉ — question hors lexique; aucune prémisse créée.";
        var r=engine.ask(goal);
        StringBuilder b=new StringBuilder("QUESTION FCI : "+r.questionId()+"\nREPONSE : "+r.answer());
        b.append("\nJUSTIFICATION : ").append(r.explanation());
        for(String step:r.proofSteps())b.append("\n  ").append(step);
        if(!r.indispensableSources().isEmpty())b.append("\nSOURCES INDISPENSABLES DANS CE MODELE : ").append(r.indispensableSources());
        for(String m:r.missing())b.append("\n  ").append(m);
        b.append("\nPREUVE FCI FORMELLEMENT VÉRIFIÉE : ").append(r.proofChecked());
        b.append("\nQUESTION ROUVERTE : ").append(r.reopened());
        return b.toString();
    }
    public void withdrawReport(String id) {
        var ids=reportFacts.remove(id(id));
        if(ids==null)throw new IllegalArgumentException("Rapport inconnu");
        for(String factId:ids)engine.withdraw(factId);
    }
    public void withdrawRule(String id) {
        if(!ruleIds.remove(id(id)))throw new IllegalArgumentException("Règle inconnue");
        engine.withdraw(id);
    }
    private static boolean uncertain(String raw) {
        String c=canonical(raw);
        return c.contains("?")||c.contains("\"")||c.contains("«")||c.contains("»")||
                c.matches(".*\\b(peut-etre|probablement|pourrait|semblerait|hypothese|citation|document|documentation)\\b.*");
    }
    private static String canonical(String phrase) {
        if(phrase==null)return "";
        return Normalizer.normalize(phrase.strip().toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
                .replaceAll("\\p{M}","").replaceAll("\\s+"," ");
    }
    private static String id(String raw) {
        if(raw==null||!raw.matches("[a-z][a-z0-9_]{0,29}"))throw new IllegalArgumentException("ID invalide");
        return raw;
    }
    /** CLI offline, full declarations required before facts/questions. */
    public static void run(InputStream in,PrintStream out) {
        var d=new CompositionalFrenchDialogue();
        out.println("=== IV-ORAMA 0.6.17 / COMPOSITION CONTRÔLÉE ===");
        out.println("expression nom | phrase positive | phrase négative");
        out.println("regle nom : si phrase et phrase, alors phrase");
        out.println("rapport nom : phrase. phrase. | ? Est-ce que phrase ? | retire rapport nom | retire regle nom | quitter");
        try(var scanner=new Scanner(in,StandardCharsets.UTF_8)) {
            while(scanner.hasNextLine()) {
                String s=scanner.nextLine().strip();
                if(s.equalsIgnoreCase("quitter"))return;
                try {
                    if(s.startsWith("expression ")) {
                        String[] p=s.substring(11).split("\\s*\\|\\s*",-1);
                        if(p.length!=3)throw new IllegalArgumentException("3 champs requis");
                        d.expression(p[0].strip(),p[1],p[2]);out.println("Expression enregistrée");
                    } else if(s.startsWith("regle ")) {
                        int sep=s.indexOf(" : ");if(sep<0)throw new IllegalArgumentException("Règle : manquant");
                        d.rule(s.substring(6,sep),s.substring(sep+3));out.println("Règle enregistrée");
                    } else if(s.startsWith("rapport ")) {
                        int sep=s.indexOf(" : ");if(sep<0)throw new IllegalArgumentException("Rapport : manquant");
                        var x=d.report(s.substring(8,sep),s.substring(sep+3));
                        out.println("Observations déclarées : "+x.accepted()+" ; phrases non interprétées : "+x.ignored());
                    } else if(s.startsWith("? "))out.println(d.question(s.substring(2)));
                    else if(s.startsWith("retire rapport ")) {d.withdrawReport(s.substring(15));out.println("Rapport retiré");}
                    else if(s.startsWith("retire regle ")) {d.withdrawRule(s.substring(13));out.println("Règle retirée");}
                    else if(!s.isEmpty())out.println("Commande non interprétée");
                } catch(RuntimeException e) {out.println("ERREUR : "+e.getMessage());}
            }
        }
    }
}
