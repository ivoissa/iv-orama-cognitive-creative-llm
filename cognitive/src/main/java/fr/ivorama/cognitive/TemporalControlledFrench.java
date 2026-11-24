package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;
import fr.ivorama.cognitive.SemanticIntermediateRepresentation.*;

/** Micro-grammaire volontairement bornée ; pas de compréhension libre revendiquée. */
public final class TemporalControlledFrench {
    private static final Pattern VALIDATE=Pattern.compile(
        "(?iu)^evenement\\s+(e[a-z0-9_]+)\\s+validation\\s+(document|dossier)\\s+([a-z0-9_]+)\\s+temps\\s+(\\d+)\\s+connu\\s+(\\d+)\\s+source\\s+([^\\s]+)$");
    private static final Pattern CANCEL=Pattern.compile(
        "(?iu)^evenement\\s+(e[a-z0-9_]+)\\s+annulation\\s+(e[a-z0-9_]+)\\s+(document|dossier)\\s+([a-z0-9_]+)\\s+temps\\s+(\\d+)\\s+connu\\s+(\\d+)\\s+source\\s+([^\\s]+)$");
    private static final Pattern ARCHIVE=Pattern.compile(
        "(?iu)^evenement\\s+(e[a-z0-9_]+)\\s+archivage\\s+(document|dossier)\\s+([a-z0-9_]+)\\s+temps\\s+(\\d+)\\s+connu\\s+(\\d+)\\s+source\\s+([^\\s]+)$");
    private static final Pattern VALIDATION_Q=Pattern.compile(
        "(?iu)^la\\s+validation\\s+(e[a-z0-9_]+)\\s+du\\s+(document|dossier)\\s+([a-z0-9_]+)\\s+est-elle\\s+active\\s+[àa]\\s+t(\\d+)\\s*\\?$ ".strip());
    private static final Pattern ARCHIVE_Q=Pattern.compile(
        "(?iu)^le\\s+(document|dossier)\\s+([a-z0-9_]+)\\s+est-il\\s+archiv[ée]\\s+[àa]\\s+t(\\d+)\\s*\\?$ ".strip());
    private static final Pattern CAUSE_CLAIM=Pattern.compile(
        "(?iu)^cause\\s+(c[a-z0-9_]+)\\s+(e[a-z0-9_]+)\\s+(e[a-z0-9_]+)\\s+connu\\s+(\\d+)\\s+source\\s+([^\\s]+)$");
    private static final Pattern CAUSE_Q=Pattern.compile(
        "(?iu)^l['’](?:evenement|événement)\\s+(e[a-z0-9_]+)\\s+a-t-il\\s+caus[ée]\\s+(e[a-z0-9_]+)\\s*\\?$");
    private static final Pattern JOURNAL_VIEW=Pattern.compile(
        "(?iu)^(.*?)\\s+selon\\s+(?:le\\s+)?journal\\s+v(\\d+)\\s*\\?$",Pattern.DOTALL);
    public record ParsedQuestion(SemanticIntermediateRepresentation frame, OptionalLong asOfJournalVersion) {
        public ParsedQuestion { Objects.requireNonNull(frame); Objects.requireNonNull(asOfJournalVersion); }
    }
    private TemporalControlledFrench() {}
    public static boolean looksLikeTemporalQuery(String text) {
        String s=Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
        boolean causal=s.startsWith("l’événement ") || s.startsWith("l'evenement ")
                || s.startsWith("l'événement ") || s.startsWith("l’evenement ");
        boolean temporal=s.startsWith("la validation ")
                || ((s.startsWith("le dossier ") || s.startsWith("le document "))
                    && s.contains("est-il archiv"));
        return causal || (temporal && s.matches("(?s).*[àa]\\s+t\\d+.*"));
    }
    public static EventFrame parseEvent(String text) {
        String s=Objects.requireNonNull(text).strip();
        Matcher m=VALIDATE.matcher(s);
        if(m.matches()) return new EventFrame(m.group(1),new EntityRef(m.group(2),m.group(3)),EventKind.VALIDATION,
                Long.parseLong(m.group(4)),Long.parseLong(m.group(5)),m.group(6),Optional.empty());
        m=CANCEL.matcher(s);
        if(m.matches()) return new EventFrame(m.group(1),new EntityRef(m.group(3),m.group(4)),EventKind.ANNULATION_VALIDATION,
                Long.parseLong(m.group(5)),Long.parseLong(m.group(6)),m.group(7),Optional.of(m.group(2)));
        m=ARCHIVE.matcher(s);
        if(m.matches()) return new EventFrame(m.group(1),new EntityRef(m.group(2),m.group(3)),EventKind.ARCHIVAGE,
                Long.parseLong(m.group(4)),Long.parseLong(m.group(5)),m.group(6),Optional.empty());
        throw new IllegalArgumentException("Événement hors grammaire RSI contrôlée");
    }
    public static CausalClaim parseCausalClaim(String text) {
        Matcher m=CAUSE_CLAIM.matcher(Objects.requireNonNull(text).strip());
        if(!m.matches()) throw new IllegalArgumentException("Hypothese causale hors grammaire");
        return new CausalClaim(m.group(1),m.group(2),m.group(3),
                Long.parseLong(m.group(4)),m.group(5));
    }
    public static ParsedQuestion parseQuery(String text) {
        String s=Objects.requireNonNull(text).strip();
        OptionalLong view=OptionalLong.empty();
        Matcher historical=JOURNAL_VIEW.matcher(s);
        if(historical.matches()) {
            view=OptionalLong.of(Long.parseLong(historical.group(2)));
            s=historical.group(1).strip()+" ?";
        }
        Matcher causal=CAUSE_Q.matcher(s);
        if(causal.matches()) return new ParsedQuestion(
                new CausalQuestionFrame(causal.group(1),causal.group(2)),view);
        return new ParsedQuestion(parseQuestion(s),view);
    }
    public static TemporalQuestionFrame parseQuestion(String text) {
        String s=Objects.requireNonNull(text).strip();
        Matcher m=VALIDATION_Q.matcher(s);
        if(m.matches()) return new TemporalQuestionFrame(new EntityRef(m.group(2),m.group(3)),
                QuestionKind.VALIDATION_ACTIVE,Long.parseLong(m.group(4)),Optional.of(m.group(1)));
        m=ARCHIVE_Q.matcher(s);
        if(m.matches()) return new TemporalQuestionFrame(new EntityRef(m.group(1),m.group(2)),
                QuestionKind.ARCHIVAGE_CONSTATE,Long.parseLong(m.group(3)),Optional.empty());
        throw new IllegalArgumentException("Question temporelle hors grammaire contrôlée");
    }
}
