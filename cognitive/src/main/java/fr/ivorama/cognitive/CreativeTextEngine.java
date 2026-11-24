package fr.ivorama.cognitive;
import java.util.*;

/** Composition symbolique bornée : lexèmes + rôles + grammaire, aucune phrase réponse stockée. */
public final class CreativeTextEngine {
 public static final class CompositionUnavailable extends IllegalArgumentException{public CompositionUnavailable(String text){super(text);}}
 public enum Style { PLAIN,POETIC }
 public record Noun(String id,String word,FrenchAgreement.Gender gender,Set<String> tags){
  public Noun{StructuredLogic.identifier(id);word=CreativeTextEngine.word(word);Objects.requireNonNull(gender);if(gender==FrenchAgreement.Gender.UNKNOWN)throw new IllegalArgumentException("Genre lexical requis");tags=Set.copyOf(tags);}
 }
 public record Verb(String id,String surface,Set<String> subjectTags,boolean transitive,boolean humanAction){
  public Verb{StructuredLogic.identifier(id);surface=word(surface);subjectTags=Set.copyOf(subjectTags);}
 }
 public record Adjective(String id,FrenchAgreement.Paradigm forms){public Adjective{StructuredLogic.identifier(id);Objects.requireNonNull(forms);for(String form:List.of(forms.masculineSingular(),forms.feminineSingular(),forms.masculinePlural(),forms.femininePlural()))CreativeTextEngine.word(form);}}
 public record Vocabulary(List<Noun> nouns,List<Verb> verbs,List<Adjective> adjectives){
  public Vocabulary{nouns=List.copyOf(nouns);verbs=List.copyOf(verbs);adjectives=List.copyOf(adjectives);
   if(nouns.isEmpty()||verbs.isEmpty()||adjectives.isEmpty()||nouns.size()>128||verbs.size()>64||adjectives.size()>64)throw new IllegalArgumentException("Lexique vide ou hors budget");
   unique(nouns.stream().map(Noun::id).toList());unique(verbs.stream().map(Verb::id).toList());unique(adjectives.stream().map(Adjective::id).toList());
   unique(nouns.stream().map(Noun::word).toList());unique(verbs.stream().map(Verb::surface).toList());
  }
  public Noun noun(String id){return nouns.stream().filter(n->n.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Nom absent du lexique"));}
  public Verb verb(String id){return verbs.stream().filter(v->v.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Verbe absent du lexique"));}
  public Adjective adjective(String id){return adjectives.stream().filter(a->a.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Adjectif absent du lexique"));}
 }
 public record Clause(String subject,String verb,Optional<String> object,Optional<String> adjective){
  public Clause{StructuredLogic.identifier(subject);StructuredLogic.identifier(verb);Objects.requireNonNull(object);Objects.requireNonNull(adjective);}
 }
 public record Plan(Style style,String theme,List<Clause> clauses){
  public Plan{Objects.requireNonNull(style);StructuredLogic.identifier(theme);clauses=List.copyOf(clauses);if(clauses.isEmpty()||clauses.size()>4)throw new IllegalArgumentException("Une à quatre propositions requises");}
  public String json(){return StructuredJsonWriter.json(Map.of("schema",clauses.stream().allMatch(c->c.subject().equals(theme))?"ivoc.content-plan.1":"ivoc.content-plan.2","scope","FICTION","style",style.name(),"theme",theme,"clauses",clauses.stream().map(c->Map.of("subject",c.subject(),"verb",c.verb(),"object",c.object().orElse(""),"adjective",c.adjective().orElse(""))).toList()));}
 }
 public record Composition(String text,Plan plan,int examined,boolean roundTripVerified){}
 private final Vocabulary vocabulary;
 public CreativeTextEngine(){this(defaults());}
 public CreativeTextEngine(Vocabulary vocabulary){this.vocabulary=Objects.requireNonNull(vocabulary);}
 public Vocabulary vocabulary(){return vocabulary;}
 public Composition compose(Style style,Optional<String> theme,int clauses,long seed){
  if(clauses<1||clauses>4)throw new IllegalArgumentException("Une à quatre propositions couvertes");
  Random random=new Random(seed);
  Noun actor=theme.map(t->vocabulary.nouns().stream().filter(n->n.word().equals(t)).findFirst().orElseThrow(()->new IllegalArgumentException("Thème absent du lexique de composition : "+t))).orElseGet(()->randomActor(style,random));
  List<Clause> candidates=new ArrayList<>();int examined=0;
  var actors=style==Style.POETIC?vocabulary.nouns().stream().filter(n->n.id().equals(actor.id())||!Collections.disjoint(n.tags(),actor.tags())).toList():List.of(actor);
  for(var subject:actors)for(var verb:vocabulary.verbs()){
   if(!allowed(subject,verb,style)||style==Style.POETIC&&!verb.humanAction())continue;
   if(!verb.transitive()){candidates.add(new Clause(subject.id(),verb.id(),Optional.empty(),Optional.empty()));examined++;}
   else for(var target:vocabulary.nouns())if(target.tags().contains("abstract")&&!target.id().equals(subject.id()))for(var adjective:vocabulary.adjectives()){
    if(++examined>4096)throw new CompositionUnavailable("Budget de composition dépassé");
    candidates.add(new Clause(subject.id(),verb.id(),Optional.of(target.id()),Optional.of(adjective.id())));
   }
  }
  if(candidates.isEmpty())throw new CompositionUnavailable("Aucune construction compatible avec le thème et le style");
  Collections.shuffle(candidates,random);var chosen=new ArrayList<Clause>();
  var verbUse=new HashMap<String,Integer>();var subjectUse=new HashMap<String,Integer>();var objectUse=new HashMap<String,Integer>();
  while(chosen.size()<clauses){
   int position=chosen.size();String previous=position==0?"":chosen.getLast().verb();
   var available=candidates.stream().filter(c->!chosen.contains(c)&&(position>0||c.subject().equals(actor.id()))).toList();
   if(available.isEmpty())break;
   var distinct=available.stream().filter(c->!c.verb().equals(previous)).toList();if(!distinct.isEmpty())available=distinct;
   var best=available.stream().min(Comparator.<Clause>comparingInt(c->verbUse.getOrDefault(c.verb(),0))
       .thenComparingInt(c->subjectUse.getOrDefault(c.subject(),0)).thenComparingInt(c->objectUse.getOrDefault(c.object().orElse(""),0))).orElseThrow();
   chosen.add(best);verbUse.merge(best.verb(),1,Integer::sum);subjectUse.merge(best.subject(),1,Integer::sum);objectUse.merge(best.object().orElse(""),1,Integer::sum);
  }
  if(chosen.size()!=clauses)throw new CompositionUnavailable("Capacité insuffisante : "+candidates.size()+" constructions distinctes disponibles pour "+clauses+" lignes demandées (thème « "+actor.word()+" », style "+style+").");
  Plan plan=readPlan(new Plan(style,actor.id(),chosen).json(),vocabulary);
  String text=String.join("\n",plan.clauses().stream().map(c->FrenchTextRealizer.realize(c,vocabulary)).toList());
  var recovered=FrenchTextRealizer.read(text,vocabulary);
  if(!recovered.equals(plan.clauses()))throw new IllegalStateException("Réalisation non fidèle au plan");
  validate(plan,vocabulary);
  return new Composition(text,plan,examined,true);
 }
 private Noun randomActor(Style style,Random random){var possible=vocabulary.nouns().stream().filter(n->vocabulary.verbs().stream().anyMatch(v->allowed(n,v,style))).toList();if(possible.isEmpty())throw new CompositionUnavailable("Aucun sujet compatible");return possible.get(random.nextInt(possible.size()));}
 static boolean allowed(Noun n,Verb v,Style style){return v.humanAction()?style==Style.POETIC:!Collections.disjoint(n.tags(),v.subjectTags());}
 public static void validate(Plan p,Vocabulary v){
  var theme=v.noun(p.theme());if(!p.clauses().getFirst().subject().equals(p.theme()))throw new IllegalArgumentException("Le premier sujet doit ancrer le thème");for(var c:p.clauses()){
   var actor=v.noun(c.subject());var verb=v.verb(c.verb());
   if((!actor.id().equals(p.theme())&&(p.style()!=Style.POETIC||Collections.disjoint(actor.tags(),theme.tags())))||!allowed(actor,verb,p.style()))throw new IllegalArgumentException("Rôle ou style non respecté");
   if(verb.transitive()!=c.object().isPresent()||c.object().isPresent()!=c.adjective().isPresent())throw new IllegalArgumentException("Valence non respectée");
   c.object().ifPresent(id->{if(!v.noun(id).tags().contains("abstract"))throw new IllegalArgumentException("Cible hors cadre déclaré");});c.adjective().ifPresent(v::adjective);
  }
 }
 public static Plan readPlan(String json,Vocabulary v){
  if(json==null||json.length()>16000)throw new IllegalArgumentException("Plan hors budget");Object raw=MiniJson.parse(json);
  if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schema","scope","style","theme","clauses"))||!("ivoc.content-plan.1".equals(m.get("schema"))||"ivoc.content-plan.2".equals(m.get("schema")))||!"FICTION".equals(m.get("scope"))||!(m.get("clauses") instanceof List<?> xs))throw new IllegalArgumentException("Contrat de plan invalide");
  List<Clause> clauses=new ArrayList<>();for(Object x:xs){if(!(x instanceof Map<?,?> c)||!c.keySet().equals(Set.of("subject","verb","object","adjective")))throw new IllegalArgumentException("Proposition invalide");clauses.add(new Clause(str(c.get("subject")),str(c.get("verb")),opt(c.get("object")),opt(c.get("adjective"))));}
  Plan p=new Plan(Style.valueOf(str(m.get("style"))),str(m.get("theme")),clauses);
  if("ivoc.content-plan.1".equals(m.get("schema"))&&clauses.stream().anyMatch(c->!c.subject().equals(p.theme())))throw new IllegalArgumentException("Le schéma v1 exige un sujet identique au thème ; variation de sujets : schéma v2 requis");
  validate(p,v);return p;
 }
 private static String str(Object x){if(!(x instanceof String s))throw new IllegalArgumentException("Chaîne requise");return s;}
 private static Optional<String> opt(Object x){String s=str(x);return s.isEmpty()?Optional.empty():Optional.of(s);}
 private static void unique(List<String> xs){if(new HashSet<>(xs).size()!=xs.size())throw new IllegalArgumentException("Entrée lexicale dupliquée");}
 private static String word(String s){if(s==null||!s.matches("[\\p{L}][\\p{L}-]*"))throw new IllegalArgumentException("Lexème invalide");return s;}
 public static Vocabulary defaults(){
  List<Noun> ns=new ArrayList<>();
  noun(ns,"mer","mer",true,"water");noun(ns,"ocean","océan",false,"water");noun(ns,"riviere","rivière",true,"water");
  noun(ns,"foret","forêt",true,"land");noun(ns,"arbre","arbre",false,"land");noun(ns,"ville","ville",true,"land");
  noun(ns,"vent","vent",false,"air");noun(ns,"pluie","pluie",true,"water");noun(ns,"neige","neige",true,"water");
  noun(ns,"lumiere","lumière",true,"light");noun(ns,"lune","lune",true,"light");noun(ns,"soleil","soleil",false,"light");
  noun(ns,"nuit","nuit",true,"time");noun(ns,"silence","silence",false,"abstract");noun(ns,"espoir","espoir",false,"abstract");
  noun(ns,"reve","rêve",false,"abstract");noun(ns,"souvenir","souvenir",false,"abstract");noun(ns,"pensee","pensée",true,"abstract");
  List<Verb> vs=List.of(new Verb("tisser","tisse",Set.of(),true,true),new Verb("murmurer","murmure",Set.of(),true,true),
   new Verb("briller","brille",Set.of("light","water"),false,false),new Verb("onduler","ondule",Set.of("water","land"),false,false),new Verb("passer","passe",Set.of("time","air"),false,false));
  List<Adjective> as=List.of(adj("fragile","fragile","fragile"),adj("doux","doux","douce"),adj("lointain","lointain","lointaine"),adj("clair","clair","claire"),adj("leger","léger","légère"));
  return new Vocabulary(ns,vs,as);
 }
 private static void noun(List<Noun> xs,String id,String word,boolean female,String tag){xs.add(new Noun(id,word,female?FrenchAgreement.Gender.FEMININE:FrenchAgreement.Gender.MASCULINE,Set.of(tag)));}
 private static Adjective adj(String id,String ms,String fs){return new Adjective(id,new FrenchAgreement.Paradigm(ms,fs,ms.endsWith("s")||ms.endsWith("x")?ms:ms+"s",fs+"s"));}
}
