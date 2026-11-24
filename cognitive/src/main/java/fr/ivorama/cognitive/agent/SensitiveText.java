package fr.ivorama.cognitive.agent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort document-level redaction; not a general secret detector. Preserves line numbering. */
final class SensitiveText {
    private static final String REDACTED = "[REDACTED: sensitive assignment]";
    private static final Pattern XML_OPEN = Pattern.compile("<([A-Za-z_][\\w:.-]*)(?:\\s[^<>]*)?>");
    private static final Pattern KEY = Pattern.compile(
            "(?i).*(?:password|passwd|secret|api[_-]?key|access[_-]?key|authorization|token|credential|private[_-]?key).*" );

    private SensitiveText() { }

    static String[] redactLines(String content) {
        String[] lines = content.split("\\R", -1);
        int yamlIndent = -1;
        String xmlClose = null;
        boolean privateKey = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.strip();
            if (privateKey || stripped.matches("-----BEGIN .*PRIVATE KEY-----")) {
                privateKey = !stripped.matches("-----END .*PRIVATE KEY-----");
                lines[i] = REDACTED;
                continue;
            }
            if (xmlClose != null) {
                if (line.contains(xmlClose)) xmlClose = null;
                lines[i] = REDACTED;
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (yamlIndent >= 0) {
                if (stripped.isEmpty() || indent > yamlIndent) { lines[i] = REDACTED; continue; }
                yamlIndent = -1;
            }
            Matcher xml = XML_OPEN.matcher(line);
            boolean sensitiveXml = false;
            while (xml.find()) {
                if (!KEY.matcher(xml.group(1)).matches()) continue;
                String close = "</" + xml.group(1) + ">";
                if (!line.substring(xml.end()).contains(close) && !xml.group().endsWith("/>")) xmlClose = close;
                sensitiveXml = true;
                break;
            }
            Matcher assignment = ReadOnlyWorkspace.SENSITIVE_ASSIGNMENT.matcher(line);
            boolean sensitiveAssignment = assignment.find();
            if (sensitiveAssignment) {
                String suffix = line.substring(assignment.end()).strip();
                if (line.charAt(assignment.end() - 1) == ':' &&
                        (suffix.isEmpty() || suffix.startsWith("#") || suffix.startsWith("|") || suffix.startsWith(">")))
                    yamlIndent = indent;
            }
            if (sensitiveXml || sensitiveAssignment) lines[i] = REDACTED;
        }
        return lines;
    }
}
