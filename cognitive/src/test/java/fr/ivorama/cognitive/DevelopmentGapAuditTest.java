package fr.ivorama.cognitive;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DevelopmentGapAuditTest {
 static final Path CATALOG=Path.of("evaluation/dev100/SCENARIOS_100_DEVELOPPEMENT.tsv"),MANIFEST=Path.of("evaluation/gaps0648/AUDIT_25.tsv");
 static DevelopmentGapAudit.Report report;
 static Set<String> ids;
 @BeforeAll static void runActualApis()throws Exception{
  report=DevelopmentGapAudit.run(CATALOG,MANIFEST);ids=new LinkedHashSet<>();report.cases().forEach(t->ids.add(t.id()));
 }
 @Test void actualEvidenceDoesNotAlterLegacyScore(){
  assertEquals(25,report.cases().size());assertTrue(report.legacySummary().startsWith("Total=100 PASS=75 FAIL=0 NON_PRIS_EN_CHARGE=25"));
  assertEquals("08f9cedce8b4107d8292f6c61ebfe0c5c375800162371ccd598ba4fef4cde81f",report.catalogHash());
  assertEquals("6e55fe14228e48886e2b086187a024c331a8a0c1c5429391998ff456081d3306",report.activationHash());
  assertTrue(report.cases().stream().noneMatch(t->t.execution().equals("FAILED")),()->report.cases().stream().filter(t->t.execution().equals("FAILED")).toList().toString());
 }
 @Test void untestedAndConfirmedLimitsNeverCountAsSupportedCases(){
  assertEquals(Set.of("DIA-19","TRA-11"),new HashSet<>(report.cases().stream().filter(t->t.execution().equals("NOT_RUN")).map(DevelopmentGapAudit.Trace::id).toList()));
  assertTrue(report.cases().stream().filter(t->t.execution().equals("PASSED")).allMatch(t->!t.checks().isEmpty()&&t.scope()!=DevelopmentGapAudit.Scope.UNVERIFIED));
  assertTrue(report.cases().stream().filter(t->t.scope()==DevelopmentGapAudit.Scope.LIMIT).allMatch(t->t.execution().equals("PASSED")));
 }
 @Test void noGapCanBeSilentlyRemovedOrDuplicated(@TempDir Path dir)throws Exception{
  var lines=Files.readAllLines(MANIFEST);Path duplicate=dir.resolve("duplicate.tsv");var more=new ArrayList<>(lines);more.add(lines.get(1));Files.write(duplicate,more);
  assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(duplicate,ids));
  Path missing=dir.resolve("missing.tsv");Files.write(missing,lines.subList(0,lines.size()-1));assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(missing,ids));
 }
 @Test void evidenceCannotBeDisabledWhileKeepingApiClaim(@TempDir Path dir)throws Exception{
  String source=Files.readString(MANIFEST);Path file=dir.resolve("invalid.tsv");Files.writeString(file,source.replace("\tAPI\tATOMIC_REPLACE\t","\tAPI\tNONE\t"));
  assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(file,ids));
  Files.writeString(file,source.replace("\tATOMIC_REPLACE\t","\tFAKE_PROBE\t"));assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(file,ids));
  Files.writeString(file,source.replace("\tCOMPONENT\tXOR\t","\tAPI\tXOR\t"));assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(file,ids));
  Files.writeString(file,source.replace("\tLIMIT\tEMPTY_DOMAIN\t","\tAPI\tEMPTY_DOMAIN\t"));assertThrows(IllegalArgumentException.class,()->DevelopmentGapAudit.readEntries(file,ids));
 }
}
