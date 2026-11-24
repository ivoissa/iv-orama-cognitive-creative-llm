package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bounded, read-only repository inspection. No process launch, network, writes or symlink following.
 * This is an application-level allowlist, not an OS-level sandbox. Run with an unprivileged identity.
 */
public final class ReadOnlyWorkspace {
    /** An allowed file is unreadable as bounded UTF-8 data; access-policy violations stay distinct. */
    public static final class SourceUnavailableException extends SecurityException {
        private static final long serialVersionUID = 1L;
        public SourceUnavailableException(String message) { super(message); }
    }
    /** Valid probe whose requested window is absent (for example after file truncation). */
    public static final class SourceWindowUnavailableException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public SourceWindowUnavailableException(String message) { super(message); }
    }
    private static final int MAX_FILES = 2_000;
    private static final int MAX_BYTES_PER_FILE = 96 * 1024;
    private static final int MAX_MATCHES = 12;
    /** Sensitive key names, including compound forms (secretKey=, jwt.secret.key=, DB_PASSWORD_PROD=, passwordHash:). */
    static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)[\"']?(?:password|passwd|secret|api[_-]?key|access[_-]?key|authorization|token|credential|private[_-]?key)[\\w.\\-\"']*\\s*[=:]");
    private static final int MAX_OUTPUT_CHARS = 3_000;
    private static final Set<String> BLOCKED_DIRS = Set.of(
            ".git", ".idea", ".gradle", ".m2", "target", "build", "out", "node_modules", ".vscode", ".svn");
    private static final Set<String> EXTENSIONS = Set.of(
            ".java", ".kt", ".groovy", ".xml", ".json", ".yaml", ".yml", ".properties",
            ".md", ".txt", ".sql", ".gradle", ".kts", ".sh", ".gitignore");
    private final Path root;

    public ReadOnlyWorkspace(Path root) throws IOException {
        Objects.requireNonNull(root, "root");
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Racine du projet non valide");
        }
    }

    public Path root() { return root; }
    public record Observation(String operation, String detail, String content, int scannedFiles, boolean truncated,
                              int lastCompleteLine, int nextLine) {
        public Observation(String operation, String detail, String content, int scannedFiles, boolean truncated) {
            this(operation,detail,content,scannedFiles,truncated,0,0);
        }
        public Observation {
            Objects.requireNonNull(operation);
            Objects.requireNonNull(detail);
            Objects.requireNonNull(content);
        }
    }

    /** Resolves only allowed source files or directories under the selected root. */
    private Path securePath(String relative, boolean fileRequired) throws IOException {
        if (relative == null || relative.length() > 240) throw new IllegalArgumentException("Chemin invalide");
        String slash = relative.replace('\\', '/');
        if (slash.startsWith("/") || slash.startsWith("~") || slash.indexOf('\0') >= 0)
            throw new SecurityException("Chemin absolu ou interdit");
        for (String segment : slash.split("/", -1)) {
            if (segment.equals("..") || segment.startsWith(".") && !segment.equals("."))
                throw new SecurityException("Segment de chemin interdit");
            if (BLOCKED_DIRS.contains(segment)) throw new SecurityException("Dossier exclu");
        }
        Path requested = root.resolve(slash.isBlank() ? "." : slash).normalize();
        if (!requested.startsWith(root)) throw new SecurityException("Chemin hors du projet");
        Path relativePath = root.relativize(requested);
        Path cursor = root;
        for (Path segment : relativePath) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) throw new SecurityException("Lien symbolique interdit");
        }
        if (!Files.exists(requested, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(relative);
        if (fileRequired) {
            if (!Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS) || !allowedFile(requested))
                throw new SecurityException("Fichier non autorisé");
        } else if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Dossier requis");
        }
        return requested;
    }

    private static boolean allowedFile(Path p) {
        String name = p.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".") || lower.contains("secret") || lower.contains("credential")
                || lower.contains("password") || lower.contains("token") || lower.contains("private")
                || lower.contains("keystore") || lower.contains("id_rsa") || lower.endsWith(".key")) return false;
        if (name.equals("Dockerfile") || name.equals("Jenkinsfile") || name.equals("pom.xml")) return true;
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private record Scan(List<Path> files, boolean truncated) { }
    private Scan files(String relativeDirectory) throws IOException {
        Path directory = securePath(relativeDirectory, false);
        List<Path> found = new ArrayList<>();
        boolean[] truncated = {false};
        Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), 24, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir) || !dir.equals(directory) &&
                        (dir.getFileName().toString().startsWith(".") || BLOCKED_DIRS.contains(dir.getFileName().toString())))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isSymbolicLink() && attrs.isRegularFile() && allowedFile(file)) {
                    if (found.size() == MAX_FILES) { truncated[0] = true; return FileVisitResult.TERMINATE; }
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        found.sort(Comparator.comparingInt((Path p) -> searchPriority(root.relativize(p).toString().replace('\\', '/'))).thenComparing(p -> root.relativize(p).toString()));
        return new Scan(List.copyOf(found), truncated[0]);
    }

    private static int searchPriority(String relative) {
        if (relative.startsWith("src/main/") || relative.startsWith("src/llm/java/") ||
                relative.startsWith("src/dl4j/java/")) return 0;
        if (relative.startsWith("src/") && !relative.contains("test/")) return 1;
        if (relative.startsWith("src/") && relative.contains("test/")) return 2;
        if (relative.equals("pom.xml") || relative.endsWith(".gradle") || relative.endsWith(".kts")) return 3;
        return 4;
    }

    /** List file names only; folder traversal capped to MAX_FILES and with a compact output. */
    public Observation list(String directory) throws IOException {
        Scan scan = files(directory);
        StringBuilder result = new StringBuilder();
        boolean truncated = scan.truncated();
        for (Path file : scan.files()) {
            String line = root.relativize(file).toString().replace('\\', '/') + "\n";
            if (result.length() + line.length() > MAX_OUTPUT_CHARS) { truncated = true; break; }
            result.append(line);
        }
        return new Observation("LIST", directory, result.toString(), scan.files().size(), truncated);
    }

    /** Fixed substring matching. No regex, scripts, or model-provided shell patterns. */
    public Observation search(String term) throws IOException {
        return search(term, ".");
    }

    /** Search one application-authorized directory, with the same scan/output bounds. */
    public Observation search(String term, String directory) throws IOException {
        if (term == null || term.isBlank() || term.length() > 120 || term.contains("\n") || term.contains("\r"))
            throw new IllegalArgumentException("Recherche vide ou trop longue");
        String needle = term.toLowerCase(Locale.ROOT);
        Scan scan = files(directory);
        StringBuilder result = new StringBuilder();
        int matches = 0;
        boolean truncated = scan.truncated();
        for (Path file : scan.files()) {
            String content = readUtf8(file);
            if (content == null) continue;
            String[] lines = SensitiveText.redactLines(content);
            int perFile = 0;
            for (int i = 0; i < lines.length; i++) {
                // Match on the REDACTED text: matching raw text would let SEARCH confirm guessed secret values.
                String visible = lines[i];
                if (!visible.toLowerCase(Locale.ROOT).contains(needle)) continue;
                String excerpt = root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1) + ": "
                        + clipped(visible, 360) + "\n";
                if (matches == MAX_MATCHES || result.length() + excerpt.length() > MAX_OUTPUT_CHARS) {
                    truncated = true; break;
                }
                result.append(excerpt);
                matches++;
                if (++perFile >= 3) break;
            }
            if (truncated && (matches == MAX_MATCHES || result.length() >= MAX_OUTPUT_CHARS - 400)) break;
        }
        return new Observation("SEARCH", term, result.toString(), scan.files().size(), truncated);
    }

    public Observation read(String relativeFile, int startLine, int maxLines) throws IOException {
        if (startLine < 1 || startLine > 100_000 || maxLines < 1 || maxLines > 60)
            throw new IllegalArgumentException("Fenêtre de lecture invalide (1 à 60 lignes)");
        Path file = securePath(relativeFile, true);
        String content = readUtf8(file);
        if (content == null) throw new SourceUnavailableException("Fichier binaire, non UTF-8 ou trop volumineux");
        String[] lines = SensitiveText.redactLines(content);
        if (startLine > lines.length) throw new SourceWindowUnavailableException("Début de lecture au-delà du fichier");
        StringBuilder result = new StringBuilder();
        int end = Math.min(lines.length, startLine + maxLines - 1);
        boolean truncated = end < lines.length;
        int lastCompleteLine = startLine - 1;
        for (int i = startLine - 1; i < end; i++) {
            String excerpt = root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1) + ": "
                    + clipped(lines[i], 800) + "\n";
            if (result.length() + excerpt.length() > MAX_OUTPUT_CHARS) { truncated = true; break; }
            result.append(excerpt);
            lastCompleteLine = i + 1;
        }
        return new Observation("READ", root.relativize(file).toString().replace('\\','/'), result.toString(), 1, truncated,
                lastCompleteLine, truncated ? lastCompleteLine + 1 : 0);
    }

    private static String readUtf8(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES_PER_FILE) return null;
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES_PER_FILE + 1);
        }
        if (bytes.length > MAX_BYTES_PER_FILE) return null;
        for (byte b : bytes) if (b == 0) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) { return null; }
    }
    private static String clipped(String text, int limit) {
        String normalized = text.replace('\0', ' ');
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
    }
}
