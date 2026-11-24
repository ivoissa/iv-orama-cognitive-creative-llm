package fr.ivorama.cognitive;
import java.time.Instant;
import java.net.URI;
import java.util.*;

/** Port documentaire : aucune réponse préenregistrée, aucune promotion en fait permanent. */
public interface WikidataSource {
 enum Relation { INSTANCE_OF("P31"),SUBCLASS_OF("P279");public final String property;Relation(String p){property=p;} }
 record Candidate(String id,String label,String description){public Candidate{id=qid(id);Objects.requireNonNull(label);Objects.requireNonNull(description);}}
 record Claim(String id,String subject,Relation relation,String object,String rank,String qualifiersJson,String referencesJson){
  public Claim{if(id==null||id.isBlank()||id.length()>180||!id.regionMatches(true,0,subject+"$",0,subject.length()+1))throw new IllegalArgumentException("Identifiant de déclaration invalide");subject=qid(subject);object=qid(object);Objects.requireNonNull(relation);if(!Set.of("normal","preferred","deprecated").contains(rank))throw new IllegalArgumentException("Rang Wikidata invalide");Objects.requireNonNull(qualifiersJson);Objects.requireNonNull(referencesJson);}
  public boolean unqualified(){return qualifiersJson.equals("{}");}
 }
 record Snapshot(String id,String label,long revision,Instant retrievedAt,URI url,List<Claim> claims,List<String> warnings,boolean cached){
  public Snapshot{id=qid(id);Objects.requireNonNull(label);if(revision<1)throw new IllegalArgumentException("Révision Wikidata absente");Objects.requireNonNull(retrievedAt);if(!url.equals(URI.create("https://www.wikidata.org/wiki/"+id)))throw new IllegalArgumentException("Source Wikidata incohérente");claims=List.copyOf(claims);warnings=List.copyOf(warnings);String expected=id;if(claims.size()>64||claims.stream().anyMatch(c->!c.subject().equals(expected)))throw new IllegalArgumentException("Déclarations incohérentes ou hors budget");}
  /** Les préférés priment, même si leur qualification empêche ensuite l'utilisation. */
  public List<Claim> usable(){return claims.stream().filter(c->!c.rank().equals("deprecated")&&c.unqualified()).filter(c->c.rank().equals("preferred")||claims.stream().noneMatch(p->p.relation()==c.relation()&&p.rank().equals("preferred"))).toList();}
 }
 List<Candidate> search(String label) throws Exception;
 Snapshot load(String id) throws Exception;
 static String qid(String value){if(value==null||!value.matches("Q[1-9][0-9]{0,11}"))throw new IllegalArgumentException("Identifiant Wikidata Q attendu");return value;}
}
