package fr.ivorama.cognitive;

import java.util.*;
import java.util.function.Function;

/** Une explication n'est produite QUE depuis un terme de preuve déjà vérifié.
 * Les lectures françaises sont une présentation, pas une nouvelle règle d'inférence.
 */
public final class FrenchProofExplanation {
    private FrenchProofExplanation() {}
    public static final class Limit extends RuntimeException{public Limit(){super("Budget de présentation de preuve dépassé");}}
    public static String render(Formula expected, ProvenanceAudit.Certificate certificate,
                                Collection<Formula> active, SemanticLexicon lexicon) {
        Objects.requireNonNull(expected); Objects.requireNonNull(certificate);
        Objects.requireNonNull(active); Objects.requireNonNull(lexicon);
        if(!certificate.proofChecked() || certificate.formalProof().isEmpty() ||
                !expected.equals(certificate.goal()))
            return "Aucune démonstration syntaxique vérifiée n'est disponible. Une conséquence sur modèles ou une déclaration seule ne suffit pas à expliquer le raisonnement.";
        Proof proof=certificate.formalProof().orElseThrow();
        ProofChecker.Result checked=ProofChecker.verify(proof,active);
        if(!checked.valid() || !expected.equals(checked.conclusion()))
            return "Certificat invalide dans le contexte courant : aucune explication produite.";
        Set<Formula> declared=new LinkedHashSet<>(active);
        if(!declared.containsAll(checked.dependencies()))
            return "Une hypothèse de la preuve n'est plus active : aucune explication produite.";
        List<String> lines=new ArrayList<>();
        describe(proof,declared,f->french(f,lexicon),lines,0);
        String basis=checked.dependencies().isEmpty()
                ? "Démonstration formelle, sans hypothèse extérieure."
                : "Démonstration correcte CONDITIONNELLEMENT aux déclarations ; leur vérité empirique n'est pas certifiée.";
        StringBuilder result=new StringBuilder(basis).append('\n');
        for(int i=0;i<lines.size();i++) result.append(i+1).append(". ").append(lines.get(i)).append('\n');
        result.append("Conclusion : ").append(french(expected,lexicon)).append('.');
        if(!certificate.dependencies().isEmpty()) {
            result.append("\nSources déclarées citées : ");
            var ids=new TreeSet<String>();
            for(var dependency:certificate.dependencies())
                for(var source:dependency.alternativeActiveSources()) ids.add(source.id());
            result.append(String.join(", ",ids)).append(" (non vérifiées indépendamment).");
        }
        return result.toString();
    }
    /** Rechecks the real proof before rendering; candidate NLP strings cannot provide a proof. */
    public static String renderChecked(Formula expected,Proof proof,Collection<Formula> active,Function<Formula,String> words){
        var checked=ProofChecker.verify(proof,active);
        if(!checked.valid()||!checked.conclusion().equals(expected))throw new IllegalArgumentException("Preuve documentaire invalide ou conclusion différente");
        var lines=new ArrayList<String>();try{describe(proof,new LinkedHashSet<>(active),words,lines,0);}catch(Limit ex){return "Preuve vérifiée ; détail abrégé car le budget de présentation est dépassé. Conclusion conditionnelle : "+words.apply(expected);}
        return String.join("\n",lines)+"\nConclusion conditionnelle : "+words.apply(expected)+". Les déclarations ne sont pas authentifiées.";
    }
    private static boolean isModusTollens(Proof.ImplicationIntroduction intro,Set<Formula> context){
        var dependencies=ProofChecker.verify(intro,context).dependencies();
        for(var f:dependencies)if(f instanceof Formula.Implies rule&&rule.premise().equals(intro.temporaryAssumption())&&!(rule.conclusion() instanceof Formula.Bottom)&&dependencies.contains(Formula.not(rule.conclusion())))return true;
        return false;
    }
    private static Formula conclusion(Proof proof, Set<Formula> context) {
        var checked=ProofChecker.verify(proof,context);
        if(!checked.valid()) throw new IllegalStateException("Sous-preuve non valide");
        return checked.conclusion();
    }
    private static void describe(Proof proof, Set<Formula> context, Function<Formula,String> words,
                                 List<String> lines,int depth) {
        if(depth>30 || lines.size()>80) throw new Limit();
        if(proof instanceof Proof.Assumption a) {
            lines.add("Hypothèse : "+words.apply(a.formula()));
        } else if(proof instanceof Proof.ModusPonens mp) {
            describe(mp.antecedent(),context,words,lines,depth+1);
            describe(mp.implication(),context,words,lines,depth+1);
            lines.add("Par modus ponens, "+words.apply(conclusion(proof,context)));
        } else if(proof instanceof Proof.AndIntroduction c) {
            describe(c.left(),context,words,lines,depth+1);
            describe(c.right(),context,words,lines,depth+1);
            lines.add("Par conjonction, "+words.apply(conclusion(proof,context)));
        } else if(proof instanceof Proof.AndLeft c) {
            describe(c.ofConjunction(),context,words,lines,depth+1);
            lines.add("Par extraction du premier terme, "+words.apply(conclusion(proof,context)));
        } else if(proof instanceof Proof.AndRight c) {
            describe(c.ofConjunction(),context,words,lines,depth+1);
            lines.add("Par extraction du second terme, "+words.apply(conclusion(proof,context)));
        } else if(proof instanceof Proof.ImplicationIntroduction intro) {
            Set<Formula> extended=new LinkedHashSet<>(context);
            extended.add(intro.temporaryAssumption());
            lines.add("Hypothèse temporaire : "+words.apply(intro.temporaryAssumption()));
            describe(intro.derivation(),extended,words,lines,depth+1);
            var goal=conclusion(proof,context);
            String rule=goal instanceof Formula.Implies neg&&neg.conclusion() instanceof Formula.Bottom
                ?(isModusTollens(intro,context)?"Par modus tollens (contraposée)":"Par introduction de la négation après contradiction")
                :"En déchargeant cette hypothèse";
            lines.add(rule+" : "+words.apply(goal));
        } else if(proof instanceof Proof.BottomElimination el) {
            describe(el.contradiction(),context,words,lines,depth+1);
            lines.add("Par élimination de ⊥ : "+words.apply(el.conclusion()));
        }
    }
    /** Phrasé limité et contrôlé : ne traduit pas les concepts inconnus par approximation. */
    static String french(Formula formula, SemanticLexicon lexicon) {
        if(formula instanceof Formula.Atom atom) {
            String raw=atom.name();
            for(String kind:List.of("document","dossier","porte","objet")) {
                int sep=raw.lastIndexOf("_"+kind+"_");
                if(sep<1) continue;
                String concept=raw.substring(0,sep);
                String id=raw.substring(sep+kind.length()+2);
                if(id.isEmpty()) continue;
                try {
                    String adj=lexicon.surfaceFor(concept,kind,SemanticLexicon.Number.SINGULAR);
                    return (kind.equals("porte")?"la ":"le ")+kind+" "+id+" est "+adj;
                } catch(IllegalArgumentException ignored) { break; }
            }
            return "proposition formelle «"+raw+"»";
        }
        if(formula instanceof Formula.Implies arrow) {
            if(arrow.conclusion() instanceof Formula.Bottom)
                return "non ("+french(arrow.premise(),lexicon)+")";
            return "si "+french(arrow.premise(),lexicon)+", alors "+french(arrow.conclusion(),lexicon);
        }
        if(formula instanceof Formula.And conjunction)
            return "("+french(conjunction.left(),lexicon)+" et "+french(conjunction.right(),lexicon)+")";
        return "contradiction ⊥";
    }
}
