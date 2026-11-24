package fr.ivorama.cognitive.agent;

import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Prepares model-proposed replacements in a disposable directory. Never writes into the input repo,
 * never runs model-generated code, and does not invoke the project's build scripts.
 * This is a Java-side permission boundary, NOT an OS sandbox (concurrent filesystem changes are possible).
 */
public final class PatchStager {
    private static final int MAX_EDITS = 3;
    private static final int MAX_FILE_BYTES = 96 * 1024;
    private static final int MAX_FRAGMENT_CHARS = 4_000;
    private static final int MAX_PREVIEW_CHARS = 9_000;
    private static final List<String> SOURCE_PREFIXES = List.of("src/main/java/", "src/test/java/", "src/llm/java/");

    public record Edit(String path, String oldText, String newText) { }
    public record FileResult(String path, String originalSha256, String stagedSha256,
                             boolean syntaxValid, List<String> parseErrors, String preview) {
        public FileResult { parseErrors = List.copyOf(parseErrors); }
    }
    public static final class PreparedPatch implements AutoCloseable {
        private final Path stagingDirectory;
        private final List<FileResult> files;
        private boolean closed;
        private PreparedPatch(Path stagingDirectory, List<FileResult> files) {
            this.stagingDirectory = stagingDirectory;
            this.files = List.copyOf(files);
        }
        /** Temporary copies are accessible for independent tests until this object is closed. */
        public Path stagingDirectory() {
            if (closed) throw new IllegalStateException("Brouillon déjà effacé");
            return stagingDirectory;
        }
        public List<FileResult> files() { return files; }
        public boolean syntaxValid() { return files.stream().allMatch(FileResult::syntaxValid); }
        @Override public void close() throws IOException {
            if (closed) return;
            closed = true;
            deleteTree(stagingDirectory);
        }
    }

    /** Validate ALL edits before creating any staged files. Any exception leaves the repo untouched. */
    public PreparedPatch prepare(Path projectRoot, List<Edit> proposals) throws IOException {
        Objects.requireNonNull(projectRoot);
        Objects.requireNonNull(proposals);
        if (proposals.isEmpty() || proposals.size() > MAX_EDITS)
            throw new IllegalArgumentException("1 à 3 modifications requises");
        Path root = projectRoot.toRealPath();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(projectRoot))
            throw new SecurityException("Racine invalide");
        List<Validated> accepted = new ArrayList<>();
        Set<Path> distinct = new HashSet<>();
        for (Edit edit : proposals) {
            if (edit == null || edit.oldText() == null || edit.newText() == null)
                throw new IllegalArgumentException("Modification incomplète");
            String path = edit.path();
            Path source = checkSource(root, path);
            if (!distinct.add(source)) throw new IllegalArgumentException("Deux modifications sur le même fichier");
            if (edit.oldText().isEmpty() || edit.oldText().length() > MAX_FRAGMENT_CHARS ||
                    edit.newText().length() > MAX_FRAGMENT_CHARS || edit.newText().indexOf('\0') >= 0 ||
                    edit.oldText().indexOf('\0') >= 0)
                throw new IllegalArgumentException("Segment vide, trop long ou binaire");
            byte[] originalBytes;
            try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                originalBytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (originalBytes.length > MAX_FILE_BYTES) throw new SecurityException("Source trop grande");
            String original = strictUtf8(originalBytes);
            if (original.indexOf('\0') >= 0) throw new SecurityException("Fichier binaire");
            int first = original.indexOf(edit.oldText());
            if (first < 0 || original.indexOf(edit.oldText(), first + 1) >= 0)
                throw new IllegalArgumentException("Ancre absente ou ambiguë : " + path);
            String updated = original.substring(0, first) + edit.newText() + original.substring(first + edit.oldText().length());
            if (updated.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES)
                throw new IllegalArgumentException("Fichier résultant trop volumineux");
            accepted.add(new Validated(path, originalBytes, updated, first, edit));
        }
        Path temp = Files.createTempDirectory("ivorama-patch-staging-");
        try {
            List<FileResult> results = new ArrayList<>();
            for (Validated item : accepted) {
                Path staged = temp.resolve(item.path()).normalize();
                if (!staged.startsWith(temp)) throw new SecurityException("Sortie hors staging");
                Files.createDirectories(staged.getParent());
                Files.writeString(staged, item.updated(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                List<String> errors = syntaxErrors(staged);
                String preview = preview(item);
                results.add(new FileResult(item.path(), sha256(item.originalBytes()),
                        sha256(item.updated().getBytes(StandardCharsets.UTF_8)), errors.isEmpty(), errors, preview));
            }
            return new PreparedPatch(temp, results);
        } catch (Exception e) {
            try { deleteTree(temp); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    private record Validated(String path, byte[] originalBytes, String updated, int start, Edit edit) { }

    private static Path checkSource(Path root, String relative) throws IOException {
        if (relative == null || relative.length() > 240 || relative.indexOf('\\') >= 0 ||
                relative.indexOf('\0') >= 0 || relative.startsWith("/") || relative.startsWith("~") ||
                SOURCE_PREFIXES.stream().noneMatch(relative::startsWith) || !relative.endsWith(".java"))
            throw new SecurityException("Chemin Java non autorisé");
        String[] parts = relative.split("/", -1);
        for (String part : parts)
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.startsWith("."))
                throw new SecurityException("Segment de chemin non autorisé");
        Path source = root;
        for (String part : parts) {
            source = source.resolve(part);
            if (Files.isSymbolicLink(source)) throw new SecurityException("Lien symbolique interdit");
        }
        if (!source.normalize().startsWith(root) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Source inexistante ou non autorisée");
        return source;
    }
    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) { throw new SecurityException("Source non UTF-8", ex); }
    }
    /** Parser only: no annotation processing, no type checking, no execution, no Maven plugins. */
    private static List<String> syntaxErrors(Path file) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return List.of("JDK requis pour vérifier la syntaxe");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(file.toFile());
            JavacTask parser;
            try {
                parser = (JavacTask) compiler.getTask(null, fm, diagnostics,
                        List.of("-proc:none", "--release", "21"), null, units);
            } catch (IllegalArgumentException releaseUnsupported) {
                // JRE without ct.sym or JDK < 21: parsing needs no platform API, so retry at the tool's default level.
                try {
                    parser = (JavacTask) compiler.getTask(null, fm, diagnostics, List.of("-proc:none"), null, units);
                } catch (IllegalArgumentException unavailable) {
                    return List.of("Vérification syntaxique indisponible : " + unavailable.getMessage());
                }
            }
            parser.parse();
        }
        List<String> errors = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR)
                errors.add("ligne " + d.getLineNumber() + " : " + d.getMessage(Locale.FRENCH));
            if (errors.size() == 8) break;
        }
        return List.copyOf(errors);
    }
    private static String preview(Validated file) {
        String original = strictLinePreview(file.edit().oldText(), '-');
        String proposed = strictLinePreview(file.edit().newText(), '+');
        int line = 1;
        String base = new String(file.originalBytes(), StandardCharsets.UTF_8);
        for (int i = 0; i < file.start(); i++) if (base.charAt(i) == '\n') line++;
        String text = "--- a/" + file.path() + "\n+++ b/" + file.path() + "\n@@ line " + line + " @@\n" + original + proposed;
        return text.length() > MAX_PREVIEW_CHARS ? text.substring(0, MAX_PREVIEW_CHARS) + "\n[aperçu tronqué]" : text;
    }
    private static String strictLinePreview(String source, char marker) {
        StringBuilder out = new StringBuilder();
        for (String line : SensitiveText.redactLines(source)) {
            if (out.length() > MAX_PREVIEW_CHARS / 2) { out.append("[extrait tronqué]\n"); break; }
            out.append(marker).append(line).append('\n');
        }
        return out.toString();
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (var walk = Files.walk(folder)) {
            for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
