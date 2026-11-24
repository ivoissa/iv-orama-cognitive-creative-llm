package fr.ivorama.cognitive.agent;

import java.util.List;
import java.util.Objects;

/** Prevents the generator from editing code it has not actually inspected through READ. */
public final class ObservedPatchGuard {
    private ObservedPatchGuard() { }

    /** Eligibility for ONE model repair, never permission to apply a normalized anchor.
     * Final edits must still pass requireWitnessed and exact unique-byte staging. */
    public static boolean whitespaceVariantWitnessed(List<ObserverSession.Evidence> evidence, PatchStager.Edit edit) {
        if (edit == null || edit.oldText() == null || edit.oldText().isBlank()) return false;
        String wanted = edit.oldText().replaceAll("\\s+", "");
        for (var observation : evidence) {
            if (!"READ".equals(observation.operation()) || !observation.request().equals(edit.path())) continue;
            String prefix = edit.path() + ":";
            StringBuilder snippet = new StringBuilder();
            int previous = -1;
            String[] lines = observation.content().split("\n", -1);
            for (int i = 0; i < lines.length - 1; i++) {
                String line = lines[i];
                int boundary = line.indexOf(": ", prefix.length());
                if (!line.startsWith(prefix) || boundary < 0 || line.contains("[REDACTED") || line.endsWith("…")) {
                    snippet.setLength(0); previous = -1; continue;
                }
                int number;
                try { number = Integer.parseInt(line.substring(prefix.length(), boundary)); }
                catch (NumberFormatException invalid) { snippet.setLength(0); previous = -1; continue; }
                if (number != previous + 1) snippet.setLength(0);
                snippet.append(line.substring(boundary + 2).replaceAll("\\s+", ""));
                previous = number;
                if (snippet.toString().contains(wanted)) return true;
            }
        }
        return false;
    }

    public static void requireWitnessed(List<ObserverSession.Evidence> evidence,
                                        List<PatchStager.Edit> edits) {
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(edits);
        for (PatchStager.Edit edit : edits) {
            if (edit == null || edit.path() == null || edit.oldText() == null || edit.oldText().isBlank())
                throw new SecurityException("Patch sans ancre observée");
            boolean witnessed = false;
            for (ObserverSession.Evidence observation : evidence) {
                if (!"READ".equals(observation.operation()) || !edit.path().equals(observation.request())) continue;
                StringBuilder source = new StringBuilder();
                String prefix = edit.path() + ":";
                String[] excerpts = observation.content().split("\n", -1);
                int previousLine = -1;
                // The final element has no terminating newline: it may be a budget-cut fragment.
                for (int i = 0; i < excerpts.length - 1; i++) {
                    String excerpt = excerpts[i];
                    if (!excerpt.startsWith(prefix)) { source.setLength(0); previousLine = -1; continue; }
                    int boundary = excerpt.indexOf(": ", prefix.length());
                    if (boundary < 0) { source.setLength(0); previousLine = -1; continue; }
                    String number = excerpt.substring(prefix.length(), boundary);
                    int line;
                    try { line = Integer.parseInt(number); }
                    catch (NumberFormatException invalid) { source.setLength(0); previousLine = -1; continue; }
                    String text = excerpt.substring(boundary + 2);
                    if (line < 1 || text.contains("[REDACTED") || text.endsWith("…")) {
                        source.setLength(0); previousLine = -1; continue;
                    }
                    if (line != previousLine + 1) source.setLength(0);
                    else if (previousLine > 0) source.append('\n');
                    // A numbered excerpt always ends with '\n', even at EOF. Only a following
                    // consecutive source line witnesses a real source line separator.
                    source.append(text);
                    previousLine = line;
                    // READ normalizes line separators; the source anchor retains its exact bytes for staging.
                    if (source.toString().contains(edit.oldText().replaceAll("\\R", "\n"))) {
                        witnessed = true; break;
                    }
                }
                if (witnessed) break;
            }
            if (!witnessed) throw new SecurityException("Ancre non visible dans une opération READ : " + edit.path());
        }
    }
}
