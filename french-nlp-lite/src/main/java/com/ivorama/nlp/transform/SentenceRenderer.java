package com.ivorama.nlp.transform;

import java.util.ArrayList;
import java.util.List;

final class SentenceRenderer {
    private SentenceRenderer() {}

    static String render(List<String> rawTokens) {return render(rawTokens,true);}

    static String render(List<String> rawTokens,boolean inferElision) {
        String result = fragment(rawTokens,inferElision);
        if (result.isEmpty()) return result;
        result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        if (result.endsWith(";")) result = result.substring(0, result.length()-1).stripTrailing();
        if (!terminalBeforeClosers(result)) result += ".";
        return result;
    }

    static String fragment(List<String> rawTokens) {return fragment(rawTokens,true);}

    static String fragment(List<String> rawTokens,boolean inferElision) {
        List<String> tokens = normalizeCommas(rawTokens);
        StringBuilder out = new StringBuilder();
        String previous = null;
        for (String token : tokens) {
            if (token == null || token.isBlank()) continue;
            boolean noSpaceBefore = isClosingPunctuation(token) || token.startsWith("'") || token.startsWith("’") || token.startsWith("-");
            boolean noSpaceAfterPrevious = previous != null &&
                    (previous.endsWith("'") || previous.endsWith("’") || "(".equals(previous) || "[".equals(previous) || previous.endsWith("-"));
            if (out.length() > 0 && !noSpaceBefore && !noSpaceAfterPrevious) out.append(' ');
            out.append(token);
            previous = token;
        }
        return applyFrenchContractions(out.toString(),inferElision).replaceAll("\s+", " ").trim();
    }

    private static boolean terminalBeforeClosers(String text) {
        int end=text.length()-1;
        while(end>=0&&(Character.isWhitespace(text.charAt(end))||"»”\")] }".indexOf(text.charAt(end))>=0))end--;
        return end>=0&&".!?…".indexOf(text.charAt(end))>=0;
    }

    private static List<String> normalizeCommas(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String t : raw) {
            if (t == null || t.isBlank()) continue;
            if (",".equals(t) && !out.isEmpty() && java.util.Set.of(",",";",":","!","?","…","(","[","«").contains(out.get(out.size()-1))) continue;
            if (SetHolder.SEPARATORS.contains(t) && !out.isEmpty() && ",".equals(out.get(out.size()-1))) out.remove(out.size()-1);
            if (isTerminalPunctuation(t) && !out.isEmpty() && (",".equals(out.get(out.size() - 1)) || ";".equals(out.get(out.size() - 1)))) {
                out.remove(out.size() - 1);
            }
            out.add(t);
        }
        return out;
    }

    private static class SetHolder { static final java.util.Set<String> SEPARATORS=java.util.Set.of(";",":"); }

    private static boolean isClosingPunctuation(String token) {
        return token.matches("[,.…%)\\]}]+") || "’".equals(token) || "'".equals(token);
    }

    private static boolean isTerminalPunctuation(String token) {
        return token.matches("[.!?…]+");
    }

    private static String applyFrenchContractions(String s,boolean inferElision) {
        // Élision simple devant voyelle ; l'h aspiré et les exceptions exigent un lexique.
        if(inferElision)s = s.replaceAll("(?iu)\\b(le|la)\\s+([aeéèêëiîïoôöuùûüœæ])", "l'$2");
        var contractions=java.util.regex.Pattern.compile("(?iuU)\\b(de|à)\\s+(le|les)\\b").matcher(s);
        return contractions.replaceAll(match->{String prep=match.group(1).toLowerCase(java.util.Locale.ROOT),article=match.group(2).toLowerCase(java.util.Locale.ROOT);
            String replacement=prep.equals("de")?(article.equals("les")?"des":"du"):(article.equals("les")?"aux":"au");
            return com.ivorama.nlp.core.LexiconMorphologicalEngine.caseLike(match.group(),replacement);
        });
    }
}
