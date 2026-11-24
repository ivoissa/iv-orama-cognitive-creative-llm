package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Sauvegarde textuelle versionnée UTF-8, relecture stricte et remplacement atomique si possible. */
public final class OntologicalGraphStore {
    private OntologicalGraphStore() {}
    private static String enc(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String dec(String s) {
        return new String(Base64.getUrlDecoder().decode(s),StandardCharsets.UTF_8);
    }
    public static void save(OntologicalKnowledgeGraph g, Path target) throws IOException {
        Objects.requireNonNull(g); Objects.requireNonNull(target);
        List<String> rows = new ArrayList<>();
        rows.add("IVOKG\t1");
        for (ConceptNode n : g.nodes())
            rows.add("N\t"+n.id()+"\t"+enc(n.label())+"\t"+n.kind());
        for (KnowledgeSourceRegistry.Source s : g.sources().all())
            rows.add("S\t"+s.id()+"\t"+enc(s.description())+"\t"+enc(s.reference())+"\t"+s.status());
        for (var e : g.aliases().entrySet()) for (String id : e.getValue().stream().sorted().toList())
            rows.add("L\t"+enc(e.getKey())+"\t"+id);
        for (OntologicalKnowledgeGraph.StoredAssertion st : g.history()) {
            RelationAssertion a = st.assertion();
            rows.add("A\t"+a.id()+"\t"+a.subjectId()+"\t"+a.predicate()+"\t"+a.objectId()
                    +"\t"+a.positive()+"\t"+a.sourceId()+"\t"+st.active());
        }
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "ivokg-", ".tmp");
        try {
            Files.write(tmp, rows, StandardCharsets.UTF_8);
            try { Files.move(tmp,absolute,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tmp,absolute,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(tmp); }
    }
    public static OntologicalKnowledgeGraph load(Path path) throws IOException {
        List<String> rows = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (rows.isEmpty() || !rows.get(0).equals("IVOKG\t1")) throw new IOException("Format IVOKG non reconnu");
        OntologicalKnowledgeGraph result = new OntologicalKnowledgeGraph();
        List<String[]> aliases = new ArrayList<>(), relations = new ArrayList<>();
        try {
            for (int i=1;i<rows.size();i++) {
                String row = rows.get(i);
                if (row.isBlank()) continue;
                String[] f = row.split("\\t", -1);
                switch (f[0]) {
                    case "N" -> { length(f,4); result.addNode(new ConceptNode(f[1],dec(f[2]),ConceptNode.Kind.valueOf(f[3]))); }
                    case "S" -> { length(f,5); result.sources().add(new KnowledgeSourceRegistry.Source(f[1],dec(f[2]),dec(f[3]), KnowledgeSourceRegistry.Status.valueOf(f[4]))); }
                    case "L" -> { length(f,3); aliases.add(f); }
                    case "A" -> { length(f,8); relations.add(f); }
                    default -> throw new IllegalArgumentException("Ligne inconnue: " + f[0]);
                }
            }
            for (String[] f : aliases) result.alias(dec(f[1]),f[2]);
            for (String[] f : relations) {
                boolean pos = truth(f[5]), active = truth(f[7]);
                result.addAssertion(new RelationAssertion(f[1],f[2],RelationAssertion.Predicate.valueOf(f[3]),f[4],pos,f[6]));
                if (!active) result.retract(f[1]);
            }
        } catch (RuntimeException e) { throw new IOException("IVOKG invalide: " + e.getMessage(), e); }
        return result;
    }
    private static void length(String[] f,int n) {
        if (f.length != n) throw new IllegalArgumentException("Colonnes incorrectes: " + Arrays.toString(f));
    }
    private static boolean truth(String s) {
        if (!s.equals("true") && !s.equals("false")) throw new IllegalArgumentException("Booléen invalide: "+s);
        return Boolean.parseBoolean(s);
    }
}
