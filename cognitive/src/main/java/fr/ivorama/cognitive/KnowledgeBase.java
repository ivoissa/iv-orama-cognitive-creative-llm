package fr.ivorama.cognitive;

import java.util.*;

/** Mémoire auditée : assertions/observations sont des prémisses, non des faits certifiés. */
public final class KnowledgeBase {
    public enum Kind { ASSERTION, OBSERVATION }
    public record Premise(String id, Formula formula, String source, Kind kind, boolean active) {}
    public record Certificate(String id, Formula conclusion, Proof proof, long versionRecorded) {}
    public record Event(long sequence, String operation, String objectId) {}
    /** Content-bearing immutable revision trace; ID alone never certifies a premise. */
    public record Revision(long sequence, String id, String action, Formula previous,
                           Formula current, String previousSource, String currentSource) {}

    private final LinkedHashMap<String,Premise> premises = new LinkedHashMap<>();
    private final LinkedHashMap<String,Certificate> certificates = new LinkedHashMap<>();
    private final ArrayList<Event> journal = new ArrayList<>();
    private final ArrayList<Revision> revisions = new ArrayList<>();
    private long version = 0;
    private static final ModelExplorer.Budget DEFAULT_BUDGET = ModelExplorer.Budget.small();

    public long version() { return version; }
    public List<Event> journal() { return List.copyOf(journal); }
    public List<Revision> revisions() { return List.copyOf(revisions); }
    public List<Premise> premisesHistory() { return List.copyOf(premises.values()); }
    public Set<Formula> activePremises() {
        Set<Formula> result = new LinkedHashSet<>();
        for (Premise p : premises.values()) if (p.active()) result.add(p.formula());
        return Set.copyOf(result);
    }

    public void addPremise(String id, Formula formula, String source, Kind kind) {
        validateId(id); Objects.requireNonNull(formula); Objects.requireNonNull(kind);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Source is mandatory");
        if (premises.containsKey(id) || certificates.containsKey(id))
            throw new IllegalArgumentException("Duplicate ID: " + id);
        premises.put(id, new Premise(id, formula, source, kind, true));
        log("PREMISE_ADD", id);
        revisions.add(new Revision(version,id,"ADD",null,formula,null,source));
    }

    public void retractPremise(String id) {
        Premise p = premises.get(id);
        if (p == null || !p.active()) throw new IllegalArgumentException("Unknown/inactive premise: " + id);
        premises.put(id, new Premise(p.id(), p.formula(), p.source(), p.kind(), false));
        log("PREMISE_RETRACT", id);
        revisions.add(new Revision(version,id,"RETRACT",p.formula(),null,p.source(),null));
    }

    /** Atomic replacement under the same ID, with content-bearing audit history. */
    public void replacePremise(String id, Formula formula, String source, Kind kind) {
        Objects.requireNonNull(formula); Objects.requireNonNull(kind);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Source is mandatory");
        Premise old = premises.get(id);
        if (old == null || !old.active()) throw new IllegalArgumentException("Unknown/inactive premise: " + id);
        premises.put(id, new Premise(id,formula,source,kind,true));
        log("PREMISE_REPLACE",id);
        revisions.add(new Revision(version,id,"REPLACE",old.formula(),formula,old.source(),source));
    }

    /** Refuse tout certificat si le contexte est contradictoire ou trop coûteux à vérifier. */
    public void recordProof(String id, Proof proof) {
        validateId(id); Objects.requireNonNull(proof);
        if (certificates.containsKey(id) || premises.containsKey(id))
            throw new IllegalArgumentException("Duplicate ID: " + id);
        Set<Formula> active = activePremises();
        if (!isContextVerifiedConsistent(active))
            throw new IllegalStateException("Premises inconsistent or resource budget exhausted");
        ProofChecker.Result result = ProofChecker.verify(proof, active);
        if (!result.valid()) throw new IllegalArgumentException(result.explanation());
        certificates.put(id, new Certificate(id, result.conclusion(), proof, version));
        log("PROOF_RECORD", id);
    }

    public boolean isProofActive(String id) {
        Certificate certificate = certificates.get(id);
        if (certificate == null) throw new IllegalArgumentException("Unknown certificate: " + id);
        Set<Formula> active = activePremises();
        return isContextVerifiedConsistent(active)
                && ProofChecker.verify(certificate.proof(), active).valid();
    }

    public Optional<Formula> supportedConclusion(String id) {
        Certificate certificate = certificates.get(id);
        if (certificate == null) return Optional.empty();
        return isProofActive(id) ? Optional.of(certificate.conclusion()) : Optional.empty();
    }

    private boolean isContextVerifiedConsistent(Set<Formula> active) {
        // Satisfiability is independent of the chosen query; ⊥ gives the same models.
        return ModelExplorer.analyze(active, Formula.Bottom.INSTANCE, DEFAULT_BUDGET).status()
                == ModelExplorer.Status.PROVEN_FALSE;
    }

    private void log(String operation, String id) {
        version++;
        journal.add(new Event(version, operation, id));
    }
    private static void validateId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Blank ID");
    }
}
