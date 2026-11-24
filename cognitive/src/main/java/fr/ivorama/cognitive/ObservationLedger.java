package fr.ivorama.cognitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Observation RAW -> calibration DECLARED -> proposition conditionnelle.
 * Le SHA-256 vérifie la stabilité des octets enregistrés, PAS l'identité d'une
 * source ni la véracité physique de la mesure. Aucune certification indépendante.
 */
public final class ObservationLedger {
    public record Reading(String id, String sourceId, Formula.Atom measuredAtom,
                          boolean rawValue, String calibrationId, Instant capturedAt,
                          Instant expiresAt, String digest) {
        public Reading {
            if (id == null || id.isBlank() || sourceId == null || sourceId.isBlank()
                    || calibrationId == null || calibrationId.isBlank())
                throw new IllegalArgumentException("Reading metadata required");
            Objects.requireNonNull(measuredAtom);
            Objects.requireNonNull(capturedAt);
            Objects.requireNonNull(expiresAt);
            Objects.requireNonNull(digest);
            if (!expiresAt.isAfter(capturedAt))
                throw new IllegalArgumentException("Expiration must follow capture");
        }
        public Formula interpreted() { return rawValue ? measuredAtom : Formula.not(measuredAtom); }
    }
    public enum Status { RECORD_INTEGRITY_OK, EXPIRED, REVOKED, CORRUPT, UNKNOWN }
    public record Event(long seq, String readingId, String action, String reason) {}
    private final LinkedHashMap<String,Reading> records = new LinkedHashMap<>();
    private final Set<String> revoked = new HashSet<>();
    private final List<Event> events = new ArrayList<>();
    private int nextId=1;

    public Reading capture(String sourceId, Formula.Atom observedAtom, boolean rawValue,
                           String declaredCalibrationId, Instant capturedAt, Instant expiresAt) {
        String id = "O" + nextId++;
        String digest = hash(id,sourceId,observedAtom,rawValue,declaredCalibrationId,capturedAt,expiresAt);
        Reading entry = new Reading(id,sourceId,observedAtom,rawValue,declaredCalibrationId,
                capturedAt,expiresAt,digest);
        records.put(id,entry);
        event(id,"CAPTURE","raw + declared calibration; no empirical certification");
        return entry;
    }
    public Reading reading(String id) {
        Reading entry = records.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown observation " + id);
        return entry;
    }
    public Status check(String id, Instant now) {
        Objects.requireNonNull(now);
        Reading reading = records.get(id);
        if (reading == null) return Status.UNKNOWN;
        if (revoked.contains(id)) return Status.REVOKED;
        return checkDetached(reading,now);
    }
    /** Integrity check on detached input, including user-made corrupted copies. */
    public static Status checkDetached(Reading reading, Instant now) {
        Objects.requireNonNull(reading); Objects.requireNonNull(now);
        String expected = hash(reading.id(),reading.sourceId(),reading.measuredAtom(),reading.rawValue(),
                reading.calibrationId(),reading.capturedAt(),reading.expiresAt());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                reading.digest().getBytes(StandardCharsets.US_ASCII))) return Status.CORRUPT;
        if (!now.isBefore(reading.expiresAt())) return Status.EXPIRED;
        return Status.RECORD_INTEGRITY_OK;
    }
    public void revoke(String id, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Revocation reason required");
        reading(id);
        if (!revoked.add(id)) throw new IllegalStateException("Already revoked");
        event(id,"REVOKED",reason);
    }
    public List<Event> journal() { return List.copyOf(events); }
    private void event(String id, String action, String reason) {
        events.add(new Event(events.size()+1L,id,action,reason));
    }
    private static String hash(String id,String sourceId,Formula.Atom atom,boolean raw,
                               String calibration,Instant at,Instant expires) {
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            // Length prefixes avoid ambiguous delimiter concatenation.
            for (String part : List.of(id,sourceId,atom.name(),String.valueOf(raw),calibration,
                    at.toString(),expires.toString())) {
                byte[] bytes=part.getBytes(StandardCharsets.UTF_8);
                md.update((byte)(bytes.length>>>24)); md.update((byte)(bytes.length>>>16));
                md.update((byte)(bytes.length>>>8)); md.update((byte)bytes.length); md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
