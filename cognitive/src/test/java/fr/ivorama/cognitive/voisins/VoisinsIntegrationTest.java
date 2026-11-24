package fr.ivorama.cognitive.voisins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class VoisinsIntegrationTest {
 @TempDir Path dir;
 @Test void originalSelfTest(){ExtracteurVoisins.selftest();}
 @Test void bundledReferencesIncludeActualExtractorRelations() throws Exception {
  var a=NuageDeMots.Referentiel.ressource("A.json");var b=NuageDeMots.Referentiel.ressource("B.json");
  for(String name:List.of("Copule","Voisin indirect"))assertNotNull(a.rang(name));
  assertNotNull(b.rang("Proximité vectorielle"));assertNotNull(b.rang("Collocation"));
 }
 @Test void vectorCsvSurvivesCloudScoring() throws Exception {
  var csv=dir.resolve("voisins.csv");Files.writeString(csv,"mot;sens;probabilite_sens;voisin;liste;relation;score\ncentre;*;1.0;proche;B;Proximité vectorielle;0.8\ncentre;*;1.0;lointain;B;Proximité vectorielle;0.2\n");
  var rows=NuageDeMots.lire(csv,"centre");var result=NuageDeMots.calculer(rows,NuageDeMots.Referentiel.ressource("A.json"),NuageDeMots.Referentiel.ressource("B.json"),0.5,1,0);
  assertEquals(List.of("proche","lointain"),result.stream().map(NuageDeMots.Resultat::voisin).toList());
  assertEquals(1,result.get(0).poids(),1e-9);assertEquals(.25,result.get(1).poids(),1e-9);
 }
 @Test void nonFiniteValuesAreNotScores() throws Exception {
  var csv=dir.resolve("invalid.csv");
  for(String value:List.of("NaN","Infinity","-0.1","1.1")){
   Files.writeString(csv,"centre;*;1;voisin;B;Collocation;"+value+"\n");
   assertThrows(IllegalArgumentException.class,()->NuageDeMots.lire(csv,"centre"));
  }
  assertThrows(IllegalArgumentException.class,()->NuageDeMots.calculer(List.of(),NuageDeMots.Referentiel.ressource("A.json"),NuageDeMots.Referentiel.ressource("B.json"),Double.NaN,1,0));
 }
 @Test void textExtractionFeedsConsumer() throws Exception {
  var corpus=dir.resolve("corpus.txt");var output=dir.resolve("voisins.csv");
  Files.writeString(corpus,"Le jardin abrite une fleur. Le jardin protège une fleur. Le ciel accueille des nuages. Le ciel est immense.");
  ExtracteurVoisins.main(new String[]{"jardin","--texte",corpus.toString(),"--min-count","1","--sortie",output.toString()});
  var rows=NuageDeMots.lire(output,"jardin");assertFalse(rows.isEmpty());assertTrue(rows.stream().anyMatch(r->r.voisin().equals("fleur")));
  assertTrue(rows.stream().allMatch(r->Double.isFinite(r.score())&&r.score()>=0&&r.score()<=1));
 }
 @Test void htmlEscapesCorpusContent(){
  var html=NuageDeMots.html("<script>",List.of(new NuageDeMots.Resultat("<img>",1,List.of("\"test\""))));
  assertFalse(html.contains("<script>"));assertFalse(html.contains("<img>"));assertTrue(html.contains("&lt;img&gt;"));
 }
 @Test void extractionCliRejectsInvalidBudgets(){
  assertThrows(IllegalArgumentException.class,()->ExtracteurVoisins.main(new String[]{"mot","--texte","absent.txt","--top","-1"}));
 }
}
