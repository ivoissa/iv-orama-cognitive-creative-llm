package fr.ivorama.cognitive;

import java.util.*;

/** Immutable semantic diagnostics. Never a Proof, empirical attestation or FCI answer. */
public record SemanticExplanation(Kind kind, Status status, ModelExplorer.Status semanticStatus,
        Optional<Formula> target, Optional<Core> rawCore, Optional<Core> mus,
        List<Core> supports, boolean alternativesComplete, long satCalls,
        long oracleAssignments, long subsetVisits, String contextDigest, String limitation) {
    public enum Kind { CONFLICT, IMPLICATION, NONE }
    public enum Status { COMPLETE, RESOURCE_LIMIT, VERIFICATION_LIMIT, NOT_APPLICABLE, FAILED }
    public record Premise(String id, Formula formula, String source, String excerpt) {
        public Premise {
            Objects.requireNonNull(id); Objects.requireNonNull(formula);
            Objects.requireNonNull(source); Objects.requireNonNull(excerpt);
            if(!id.matches("[A-Za-z][A-Za-z0-9_-]{0,127}") || source.isBlank())
                throw new IllegalArgumentException("Unique premise id and declared source required");
        }
    }
    /** For IMPLICATION, UNSAT means premises AND negated target, not premises alone. */
    public record Core(List<Premise> premises, boolean independentlyVerified, boolean subsetMinimal) {
        public Core {
            premises=List.copyOf(premises);
            if(subsetMinimal && !independentlyVerified)throw new IllegalArgumentException("Minimality requires independent verification");
        }
        public List<String> ids() { return premises.stream().map(Premise::id).toList(); }
    }
    public SemanticExplanation {
        Objects.requireNonNull(kind);Objects.requireNonNull(status);Objects.requireNonNull(semanticStatus);
        Objects.requireNonNull(target);Objects.requireNonNull(rawCore);Objects.requireNonNull(mus);
        supports=List.copyOf(supports);Objects.requireNonNull(contextDigest);Objects.requireNonNull(limitation);
        if(mus.isPresent() && !mus.get().subsetMinimal())throw new IllegalArgumentException("MUS must be independently checked");
        if(supports.stream().anyMatch(c->!c.subsetMinimal()))throw new IllegalArgumentException("Unverified support");
        if(alternativesComplete && status!=Status.COMPLETE)throw new IllegalArgumentException("Incomplete alternatives");
    }
    public boolean syntacticProofCertificate() { return false; }
    public boolean empiricallyVerified() { return false; }
    public String describe() {
        var out=new StringBuilder("Diagnostic sémantique LogicNG : "+kind+" / "+status+". ");
        mus.ifPresent(c->out.append("MUS minimal par inclusion (pas nécessairement de cardinalité minimale) : ").append(c.ids()).append(". "));
        target.ifPresent(t->out.append("Cible sémantique : ").append(t).append(". "));
        String coreLabel=kind==Kind.IMPLICATION?"Noyau UNSAT sous l'hypothèse technique ¬cible (non source déclarée) : ":"Noyau UNSAT du contexte : ";
        rawCore.ifPresent(c->out.append(coreLabel).append(c.ids()).append(c.independentlyVerified()?" (vérifié par modèles). ":" (non vérifié indépendamment). "));
        if(!supports.isEmpty())out.append("Supports minimaux par inclusion : ").append(supports.stream().map(Core::ids).toList()).append(" ; alternatives complètes : ").append(alternativesComplete).append(". ");
        LinkedHashMap<String,Premise> sources=new LinkedHashMap<>();
        rawCore.ifPresent(c->c.premises().forEach(p->sources.put(p.id(),p)));
        mus.ifPresent(c->c.premises().forEach(p->sources.put(p.id(),p)));
        supports.forEach(c->c.premises().forEach(p->sources.put(p.id(),p)));
        sources.values().forEach(p->out.append("\n").append(p.id()).append(" | source déclarée=").append(StructuredJsonWriter.json(p.source())).append(" | extrait=").append(StructuredJsonWriter.json(p.excerpt())));
        return out+"\n"+limitation+" Ce diagnostic ne constitue pas un certificat de preuve syntaxique ; la preuve contrôlée est indiquée séparément. La vérité empirique des sources n'est pas certifiée.";
    }
}
