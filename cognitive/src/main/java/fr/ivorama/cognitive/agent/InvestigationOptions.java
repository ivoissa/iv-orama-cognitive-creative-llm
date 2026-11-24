package fr.ivorama.cognitive.agent;

import java.util.*;

/** Caller-declared task contract. The reading policy never changes the logical kernel. */
public record InvestigationOptions(Goal goal, boolean opportunistic, boolean paginate,
                                   boolean coverageFirst, ProbePolicy probePolicy) {
    public enum Goal { DIAGNOSE, AUDIT_ALL, REVIEW_MODEL }
    public enum ProbePolicy { LEGACY, COVERAGE_FIRST, BALANCED }
    public InvestigationOptions {
        Objects.requireNonNull(goal);
        Objects.requireNonNull(probePolicy);
        if(coverageFirst && probePolicy!=ProbePolicy.COVERAGE_FIRST)
            throw new IllegalArgumentException("coverageFirst=true requiert COVERAGE_FIRST");
        if(probePolicy==ProbePolicy.COVERAGE_FIRST)coverageFirst=true;
    }
    /** Preserve the existing 0.7.9-0.7.11 four-argument API. */
    public InvestigationOptions(Goal goal,boolean opportunistic,boolean paginate,boolean coverageFirst) {
        this(goal,opportunistic,paginate,coverageFirst,
                coverageFirst?ProbePolicy.COVERAGE_FIRST:ProbePolicy.LEGACY);
    }
    /** Preserve the original 0.7.8 three-argument API. */
    public InvestigationOptions(Goal goal,boolean opportunistic,boolean paginate) {
        this(goal,opportunistic,paginate,false,ProbePolicy.LEGACY);
    }
    public static InvestigationOptions defaults() {
        return new InvestigationOptions(Goal.DIAGNOSE,true,true);
    }
    public static InvestigationOptions fromSystemProperties() {
        boolean coverage=bool("ivorama.agent.coverageFirst",false);
        String setting=System.getProperty("ivorama.agent.probePolicy","").trim();
        ProbePolicy policy=setting.isBlank()?(coverage?ProbePolicy.COVERAGE_FIRST:ProbePolicy.LEGACY)
                :ProbePolicy.valueOf(setting.toUpperCase(Locale.ROOT));
        if(coverage && policy!=ProbePolicy.COVERAGE_FIRST)
            throw new IllegalArgumentException("Options incompatibles : coverageFirst et probePolicy");
        return new InvestigationOptions(
                Goal.valueOf(System.getProperty("ivorama.agent.goal","DIAGNOSE").toUpperCase(Locale.ROOT)),
                bool("ivorama.agent.opportunistic",true),bool("ivorama.agent.paginate",true),
                coverage,policy);
    }
    private static boolean bool(String key,boolean fallback) {
        String value=System.getProperty(key,Boolean.toString(fallback));
        if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Booléen attendu : "+key);
        return Boolean.parseBoolean(value);
    }
    public boolean auditsAll() { return goal!=Goal.DIAGNOSE; }
}
