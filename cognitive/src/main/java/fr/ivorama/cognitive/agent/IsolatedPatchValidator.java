package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;

/** Fixed, caller-selected Maven tests in disposable copies; NOT an OS security sandbox.
 * Repository build/test code executes with the current user's permissions. Never accepts model commands. */
public final class IsolatedPatchValidator {
    public record Plan(String mavenExecutable, String tests, Duration timeout, boolean localLlmProfile) {
        public Plan {
            if (mavenExecutable == null || mavenExecutable.isBlank() || tests == null
                    || tests.length() > 300 || !tests.matches("[A-Za-z0-9_.$]+(,[A-Za-z0-9_.$]+){0,4}")
                    || timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                    || timeout.compareTo(Duration.ofMinutes(5)) > 0)
                throw new IllegalArgumentException("Plan de validation invalide : 1 à 5 classes de tests, délai 1..300 s");
        }
    }
    public enum Status { NOT_REQUESTED, SYNTAX_INVALID, BASELINE_BUILD_FAILED, BASELINE_NOT_REPRODUCED,
        CANDIDATE_FAILED, TEST_COVERAGE_REDUCED, TIMEOUT, INFRA_UNAVAILABLE, FIX_VERIFIED }
    public record Run(int exitCode, boolean timedOut, long elapsedMillis, int tests, int failures,
                      int errors, int skipped, String log) { }
    public record Result(Status status, Run baseline, Run candidate, String detail) {
        public boolean verified() { return status == Status.FIX_VERIFIED; }
        public static Result notRequested() {
            return new Result(Status.NOT_REQUESTED, null, null, "Aucune compilation ni exécution de tests demandée");
        }
    }

    public Result validate(Path project, PatchStager.PreparedPatch patch, Plan plan) throws IOException {
        Objects.requireNonNull(plan);
        if (!patch.syntaxValid()) return new Result(Status.SYNTAX_INVALID, null, null, "Syntaxe refusée avant exécution");
        // Generated tests must never replace the independent acceptance criterion.
        for (var file : patch.files())
            if (!file.path().startsWith("src/main/java/") && !file.path().startsWith("src/llm/java/"))
                throw new SecurityException("La validation interdit de modifier les tests d'acceptation");
        long deadline = System.nanoTime() + plan.timeout().toNanos();
        Path temp = Files.createTempDirectory("ivorama-validation-");
        try {
            Path before = temp.resolve("baseline"), after = temp.resolve("candidate");
            copyProject(project.toRealPath(), before);
            copyProject(before, after);
            for (var file : patch.files()) {
                Path original = before.resolve(file.path());
                if (!sha(original).equals(file.originalSha256())) throw new IOException("Source changée après observation : " + file.path());
                Path staged = patch.stagingDirectory().resolve(file.path());
                if (!sha(staged).equals(file.stagedSha256())) throw new IOException("Brouillon changé avant validation");
                Files.copy(staged, after.resolve(file.path()), StandardCopyOption.REPLACE_EXISTING);
            }
            Run baseline;
            try { baseline = run(before, plan, deadline); }
            catch (IOException unavailable) {
                return new Result(Status.INFRA_UNAVAILABLE, null, null, unavailable.getClass().getSimpleName() + ": " + unavailable.getMessage());
            }
            if (baseline.timedOut()) return new Result(Status.TIMEOUT, baseline, null, "Délai total atteint pendant la référence");
            if (baseline.tests() == 0 || baseline.exitCode() != 0 && baseline.failures() + baseline.errors() == 0)
                return new Result(Status.BASELINE_BUILD_FAILED, baseline, null, "Échec de construction ou aucun test exécuté ; défaut non reproduit");
            if (baseline.exitCode() == 0 || baseline.failures() + baseline.errors() == 0)
                return new Result(Status.BASELINE_NOT_REPRODUCED, baseline, null, "Les tests choisis ne reproduisent pas le défaut");
            Run candidate = run(after, plan, deadline);
            if (candidate.timedOut()) return new Result(Status.TIMEOUT, baseline, candidate, "Délai total atteint pendant le candidat");
            if (candidate.exitCode() != 0 || candidate.tests() == 0 || candidate.failures() + candidate.errors() > 0)
                return new Result(Status.CANDIDATE_FAILED, baseline, candidate, "La correction ne passe pas les mêmes tests");
            if (candidate.tests() < baseline.tests() || candidate.skipped() > baseline.skipped())
                return new Result(Status.TEST_COVERAGE_REDUCED, baseline, candidate, "Moins de tests ou davantage de tests ignorés");
            return new Result(Status.FIX_VERIFIED, baseline, candidate,
                    "Échec reproduit puis tests sélectionnés réussis ; aucune garantie hors de ces tests");
        } finally { deleteTree(temp); }
    }

    private static void copyProject(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        if (!Files.isRegularFile(from.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("pom.xml requis");
        Files.copy(from.resolve("pom.xml"), to.resolve("pom.xml"));
        Path sources = from.resolve("src");
        if (!Files.isDirectory(sources, LinkOption.NOFOLLOW_LINKS)) throw new IOException("src requis");
        long total = 0;
        int count = 0;
        try (var paths = Files.walk(sources, 30)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new SecurityException("Lien symbolique dans la copie de validation");
                Path destination = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else if (Files.isRegularFile(path)) {
                    long size = Files.size(path);
                    if (++count > 4000 || size > 4 * 1024 * 1024 || (total += size) > 64 * 1024 * 1024)
                        throw new IOException("Projet trop volumineux pour cette validation bornée");
                    Files.copy(path, destination);
                }
            }
        }
    }
    private static Run run(Path root, Plan plan, long deadline) throws IOException {
        long start = System.nanoTime(), left = deadline - start;
        if (left <= 0) return new Run(-1, true, 0, 0, 0, 0, 0, "Budget écoulé avant lancement");
        List<String> command = new ArrayList<>(List.of("-B", "-o", "-Dstyle.color=never",
                "-DfailIfNoTests=true", "-Dtest=" + plan.tests()));
        if (plan.localLlmProfile()) command.add("-Plocal-llm");
        command.add("test");
        Path log = root.resolve("validation.log");
        Process process = mavenProcess(plan.mavenExecutable(), command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean timeout;
        try { timeout = !process.waitFor(left, TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new IOException("Validation interrompue", interrupted);
        }
        if (timeout) stop(process);
        int[] counts = readReports(root.resolve("target/surefire-reports"));
        String excerpt;
        try (var input = Files.newInputStream(log)) { excerpt = new String(input.readNBytes(20_000), java.nio.charset.StandardCharsets.UTF_8); }
        return new Run(timeout ? -1 : process.exitValue(), timeout, (System.nanoTime() - start) / 1_000_000,
                counts[0], counts[1], counts[2], counts[3], excerpt);
    }
    private static ProcessBuilder mavenProcess(String executable, List<String> arguments) {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        if (executable.equals("mvn")) {
            String launcher = windows ? "mvn.cmd" : "mvn";
            String mavenHome = System.getProperty("maven.home", "");
            Path installed = Path.of(mavenHome, "bin", launcher);
            executable = !mavenHome.isBlank() && Files.isRegularFile(installed)
                    ? installed.toAbsolutePath().toString() : launcher;
        }

        List<String> command = new ArrayList<>();
        boolean batch = windows && executable.toLowerCase(Locale.ROOT).endsWith(".cmd");
        if (batch) {
            if (executable.chars().anyMatch(c -> c == '"' || c < 32)) {
                throw new IllegalArgumentException("Chemin du lanceur Maven invalide");
            }
            // Expand the path inside quotes with delayed expansion disabled.
            // The fixed arguments and the validated test names contain no shell operators.
            Path shell = Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe");
            command.addAll(List.of(shell.toString(), "/d", "/v:off", "/c",
                    "%IVORAMA_MAVEN_LAUNCHER%"));
        } else {
            command.add(executable);
        }
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (batch) {
            builder.environment().put("IVORAMA_MAVEN_LAUNCHER", '"' + executable + '"');
        }
        return builder;
    }

    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try { process.waitFor(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    private static int[] readReports(Path directory) throws IOException {
        int[] totals = new int[4];
        if (!Files.isDirectory(directory)) return totals;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            try (var reports = Files.newDirectoryStream(directory, "TEST-*.xml")) {
                for (Path report : reports) {
                    if (Files.size(report) > 4 * 1024 * 1024) throw new IOException("Rapport trop volumineux");
                    var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                    String[] keys = {"tests", "failures", "errors", "skipped"};
                    for (int i = 0; i < keys.length; i++) totals[i] += Integer.parseInt(suite.getAttribute(keys[i]));
                }
            }
        } catch (Exception invalid) { throw new IOException("Rapport de tests invalide", invalid); }
        return totals;
    }
    private static String sha(Path path) throws IOException {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
