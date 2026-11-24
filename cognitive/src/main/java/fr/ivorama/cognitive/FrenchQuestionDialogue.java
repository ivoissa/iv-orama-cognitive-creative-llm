package fr.ivorama.cognitive;

import java.util.*;
import java.nio.file.Path;
import java.io.IOException;

/** Adapte le langage contrôlé au cycle interrogatif FCI/RDO existant. */
public final class FrenchQuestionDialogue {
    /** La réponse linguistique peut être en attente, sans rapport logique fictif. */
    public static final class Reply {
        private final String questionId;
        private final String text;
        private final Optional<IntegratedQuestionCycle.Report> report;
        private final boolean awaiting;
        public Reply(String id, String text, IntegratedQuestionCycle.Report report) {
            this(id,text,report,report==null);
        }
        /** Etat de l'obligation, distinct de l'absence de rapport oui/non. */
        public Reply(String id, String text, IntegratedQuestionCycle.Report report,boolean awaiting) {
            this.questionId=Objects.requireNonNull(id);
            this.text=Objects.requireNonNull(text);
            this.report=Optional.ofNullable(report);
            this.awaiting=awaiting;
        }
        public String questionId() { return questionId; }
        public String text() { return text; }
        public boolean pending() { return awaiting; }
        public Optional<IntegratedQuestionCycle.Report> reportOptional() { return report; }
        public IntegratedQuestionCycle.Report report() {
            return report.orElseThrow(()->new IllegalStateException(
                    "Question en cours de clarification ; aucun rapport logique disponible"));
        }
    }
    private record Pending(String phrase, Map<String,String> decisions,
                           String word, List<String> candidates, String lexicalFingerprint) {
        Pending { decisions=Map.copyOf(decisions); candidates=List.copyOf(candidates); }
    }
    private final Map<String,Pending> pending=new LinkedHashMap<>();
    private final Object dialogueOwner=new Object();
    /** Lectures de portée attachées à l'acte, jamais reconstruites après clarification. */
    private final Map<String,QuantifierScopeFrench.Readings> scopePending=new LinkedHashMap<>();
    private final Map<String,String> scopeChosen=new LinkedHashMap<>();
    public List<String> pendingIds() { return List.copyOf(pending.keySet()); }
    /** Lexical/syntactic interpretation; FCI acts are only opened by ask, never by parsing. */
    private ControlledFrench.Interpretation parse(String phrase,Map<String,String> decisions) {
        String normalized=phrase.toLowerCase(Locale.ROOT);
        var finite=finiteQuantifiers.parse(phrase);
        if(finite.isPresent()) return finite.orElseThrow().interpretation();
        var quantifier=quantifiedRelations.parse(phrase);
        if(quantifier.isPresent()) return quantifier.orElseThrow().interpretation();
        if(normalized.startsWith("si ")) {
            var conditional=relations.tryConditional(phrase);
            if(conditional.isPresent()) return conditional.get();
        }
        // La virgule/« alors » fixe la frontière des deux opérandes :
        // une conjonction DANS le conséquent ne doit pas être rejetée comme
        // une coordination de la phrase entière.
        if(normalized.startsWith("si ") && normalized.contains(" et ")) {
            var rule=java.util.regex.Pattern.compile("(?s)^si\\s+(.+?),\\s*alors\\s+(.+?)[.]?$")
                    .matcher(normalized.strip());
            if(rule.matches()) {
                var before=CompositionalFrench.analyse(language,rule.group(1).strip(),decisions);
                var after=CompositionalFrench.analyse(language,rule.group(2).strip(),decisions);
                if(before.act()!=ControlledFrench.Act.ASSERT || after.act()!=ControlledFrench.Act.ASSERT)
                    throw new IllegalArgumentException("Les deux membres d'une implication doivent être des assertions");
                return new ControlledFrench.Interpretation(ControlledFrench.Act.ASSERT,
                    List.of(Formula.implies(before.tree().formula(),after.tree().formula())),
                    "Conditionnelle composée dont chaque feuille est contrôlée");
            }
        }
        if(normalized.contains(" et ")) {
            // The legacy compositional grammar handles property ellipsis and lexical choices.
            // Explicit mixed clauses are composed only when a full binary relation appears.
            if(!hasRelationalConjunct(phrase))
                return CompositionalFrench.analyse(language,phrase,decisions).interpretation();
            return mixedConjunction(phrase,decisions);
        }
        if(relations.looksRelational(phrase)) return relations.interpret(phrase);
        return language.interpret(phrase,decisions);
    }
    private boolean hasRelationalConjunct(String phrase) {
        String t=phrase.toLowerCase(Locale.ROOT).strip().replaceFirst("[?.]$", "");
        if(t.startsWith("est-ce que ")) t=t.substring(11);
        else if(t.startsWith("est ce que ")) t=t.substring(11);
        for(String segment:t.split(" et ")) if(relations.looksRelational(segment)) return true;
        return false;
    }
    private ControlledFrench.Interpretation mixedConjunction(String phrase,Map<String,String> choices) {
        String t=phrase.toLowerCase(Locale.ROOT).strip();
        boolean question=t.endsWith("?");
        t=t.replaceFirst("[?.]$", "").strip();
        if(t.startsWith("est-ce que ")) {
            if(!question) throw new IllegalArgumentException("Interrogation sans '?'");
            t=t.substring(11);
        } else if(t.startsWith("est ce que ")) {
            if(!question) throw new IllegalArgumentException("Interrogation sans '?'");
            t=t.substring(11);
        }
        String[] segments=t.split(" et ",-1);
        if(segments.length<2 || segments.length>8)
            throw new IllegalArgumentException("Coordination hors limite : 2 à 8 clauses");
        Formula combined=null;
        Map<String,Integer> occurrences=new HashMap<>();
        for(String segment:segments) {
            Map<String,String> localChoices=choices;
            if(!relations.looksRelational(segment)) {
                String[] words=segment.strip().split("\\s+");
                String adjective=words[words.length-1];
                int occurrence=occurrences.merge(adjective,1,Integer::sum)-1;
                String selected=choices.getOrDefault(adjective+"#"+occurrence,choices.get(adjective));
                localChoices=selected==null?Map.of():Map.of(adjective,selected);
            }
            var meaning=relations.looksRelational(segment)?relations.interpret(segment)
                :language.interpret(segment+".",localChoices);
            if(meaning.act()!=ControlledFrench.Act.ASSERT || meaning.formulas().size()!=1)
                throw new IllegalArgumentException("Coordination exige des propositions complètes");
            var f=meaning.formulas().get(0);
            combined=combined==null?f:Formula.and(combined,f);
        }
        return new ControlledFrench.Interpretation(question?ControlledFrench.Act.ASK
            :ControlledFrench.Act.ASSERT,List.of(combined),
            "Conjonction de clauses relationnelles et prédicatives contrôlées");
    }
    private Reply clarificationReply(String id,Pending p) {
        var scope=scopePending.get(id);
        if(scope!=null) return new Reply(id,"Question "+id+" : "+p.phrase()
            +"\nDeux lectures de la négation sur "+scope.domainSize()+" entités déclarées :"
            +"\n  pas_tous : "+scope.labelNotAll()
            +"\n  aucun : "+scope.labelNone()
            +"\nPréciser : precise "+id+" pas_tous | aucun. Une seule faille FCI reste ouverte.",null);
        return new Reply(id,"Question "+id+" : "+p.phrase()
            +"\nClarification nécessaire pour « "+p.word()+" » : "
            +String.join(" / ",p.candidates())
            +". Préciser : precise "+id+" <sens>. Une seule faille reste ouverte.",null);
    }
    private final ControlledFrench language = new ControlledFrench();
    private final RelationalFrench relations = new RelationalFrench(language);
    private final QuantifiedRelationalFrench quantifiedRelations = new QuantifiedRelationalFrench(language,relations);
    private final FiniteQuantifierFrench finiteQuantifiers = new FiniteQuantifierFrench(language);
    public RelationalFrench relations() { return relations; }
    /** Flexion plurielle explicite, sans deviner la conjugaison d'un verbe appris. */
    public void registerRelation(String concept,String subjectKind,String objectKind,
                                 String activeVerb,String inverseVerb,String activePlural) {
        relations.register(concept,subjectKind,objectKind,activeVerb,inverseVerb,activePlural);
    }
    /** A new verb maps to a binary concept, but does not assert any fact. */
    public void registerRelation(String concept,String subjectKind,String objectKind,
                                 String activeVerb,String inverseVerb) {
        relations.register(concept,subjectKind,objectKind,activeVerb,inverseVerb);
    }
    private final IntegratedQuestionCycle cognitive = new IntegratedQuestionCycle();
    private final TemporalEventLedger temporalLedger = new TemporalEventLedger();
    private final Set<String> temporalQuestions = new LinkedHashSet<>();
    /** Les événements sont des déclarations datées, jamais des faits certifiés. */
    public void declareTemporalEvent(String phrase) {
        temporalLedger.admit(TemporalControlledFrench.parseEvent(phrase));
    }
    public void retractTemporalEvent(String id,String reason) { temporalLedger.retract(id,reason); }
    /** Seule une hypothese explicite est inscrite ; jamais une preuve de cause. */
    public void declareCausalClaim(String phrase) {
        temporalLedger.admitCausalClaim(TemporalControlledFrench.parseCausalClaim(phrase));
    }
    public void retractCausalClaim(String id,String reason) { temporalLedger.retractCausalClaim(id,reason); }
    public TemporalEventLedger temporalLedger() { return temporalLedger; }
    private Reply temporalReply(String id) {
        var question=cognitive.temporalQuestion(id);
        long view=question.asOfJournalVersion().orElse(temporalLedger.version());
        var result=temporalLedger.assessAtVersion(question.frame(),view);
        cognitive.recordTemporalFinding(id,result.finding(),result.ledgerVersion());
        String text=switch(result.finding()) {
            case ACTIVE_CONDITIONALLY -> "Oui, cette validation identifiée est active selon les événements déclarés.";
            case INACTIVE_CONDITIONALLY -> "Non, cette validation identifiée a été annulée selon les événements déclarés.";
            case ARCHIVED_CONDITIONALLY -> "Un événement d'archivage a été déclaré. Sa réalité n'est pas vérifiée.";
            case CAUSAL_CLAIM_ONLY -> "Une hypothèse causale a été DECLAREE, sans démonstration de causalité. La question reste ouverte.";
            case NOT_ESTABLISHED -> "Indéterminé : aucune conclusion ne peut être justifiée par les événements actifs.";
        };
        text="Question temporelle/causale "+id+" : "+question.expression()+"\n"+text
            +"\n"+result.explanation()+"\nSupports événementiels : "+result.supports()
            +" ; version "+result.ledgerVersion()+" du journal"
            +(question.asOfJournalVersion().isPresent() ? " (vue historique figée)" : " (vue actuelle)");
        return new Reply(id,text,null,!result.determined());
    }
    private final Map<String,String> originalQuestions = new LinkedHashMap<>();
    private final Set<String> notInterpreted=new LinkedHashSet<>();
    private final Set<String> explanatory=new LinkedHashSet<>();
    private final Set<String> investigatory=new LinkedHashSet<>();
    private final Map<String,String> unparsedReasons=new LinkedHashMap<>();
    private static final String WHY="pourquoi ";
    private static final String HOW="comment savoir si ";
    private static final String HOW_ALT="comment peut-on savoir si ";
    private static final String HOW_ALT2="comment peut on savoir si ";
    private Reply unparsed(String phrase,String cause) {
        String id=cognitive.openOwnedUninterpreted(phrase,cause,dialogueOwner);
        notInterpreted.add(id); originalQuestions.put(id,phrase); unparsedReasons.put(id,cause);
        return new Reply(id,"Question "+id+" enregistrée mais NON INTERPRÉTÉE : "+cause
                +". Aucune formule ni réponse supposée. annule "+id+" pour retirer.",null);
    }
    private static String stripLeading(String value,String prefix) {
        return value.substring(prefix.length()).strip();
    }
    /** The WHY request is its own FCI obligation; it never creates a yes/no Q. */
    public Reply askWhy(String phrase) {
        String normalized=phrase.strip().toLowerCase(Locale.ROOT);
        if(!normalized.startsWith(WHY)) return unparsed(phrase,"Cette phrase n'est pas une demande POURQUOI");
        String subject=stripLeading(phrase.strip(),WHY);
        ControlledFrench.Interpretation parsed;
        try { parsed=parse(subject,Map.of()); }
        catch(IllegalArgumentException|IllegalStateException ex) { return unparsed(phrase,ex.getMessage()); }
        if(parsed.act()!=ControlledFrench.Act.ASK || parsed.formulas().size()!=1)
            return unparsed(phrase,"La proposition à expliquer n'est pas interprétable");
        var r=cognitive.askExplanation(phrase,parsed.formulas().get(0));
        explanatory.add(r.id()); originalQuestions.put(r.id(),phrase);
        return renderExplanation(r);
    }
    /** Investigation is a request for a procedure, not a sensor reading or a fact. */
    public Reply askHow(String phrase) {
        String normalized=phrase.strip().toLowerCase(Locale.ROOT);
        String prefix=normalized.startsWith(HOW)?HOW:normalized.startsWith(HOW_ALT)?HOW_ALT:HOW_ALT2;
        String proposition=stripLeading(phrase.strip(),prefix);
        ControlledFrench.Interpretation parsed;
        try { parsed=parse("est-ce que "+proposition,Map.of()); }
        catch(IllegalArgumentException|IllegalStateException ex) { return unparsed(phrase,ex.getMessage()); }
        if(parsed.act()!=ControlledFrench.Act.ASK || parsed.formulas().size()!=1)
            return unparsed(phrase,"Proposition d'investigation non interprétable");
        var result=cognitive.askInvestigation(phrase,parsed.formulas().get(0));
        investigatory.add(result.id()); originalQuestions.put(result.id(),phrase);
        return renderInvestigation(result);
    }
    private Reply renderExplanation(IntegratedQuestionCycle.ExplanationReport result) {
        String id=result.id();
        var status=result.status();
        String text;
        if(status==IntegratedQuestionCycle.ExplanationStatus.PROVEN_CONDITIONALLY ||
            status==IntegratedQuestionCycle.ExplanationStatus.PROVEN_FORMALLY) {
            Set<Formula> active=new LinkedHashSet<>();
            for(var p:cognitive.premises()) if(p.active()) active.add(p.formula());
            text=FrenchProofExplanation.render(result.goal(),result.certificate().orElseThrow(),active,language.lexicon());
        } else text=switch(status) {
            case DIRECT_ASSERTION_ONLY -> "La proposition est seulement déclarée : la répéter ne fournit pas d'explication déductive indépendante.";
            case NO_CHECKED_PROOF -> "Une conséquence sur modèles existe, mais aucune preuve syntaxique vérifiée ne permet de construire l'explication.";
            case NOT_ESTABLISHED -> "La proposition à expliquer n'est pas établie par les connaissances disponibles.";
            case INCONSISTENT -> "Connaissances contradictoires : aucune justification acceptée.";
            case RESOURCE_LIMIT -> "Budget logique atteint : aucune justification établie.";
            default -> throw new IllegalStateException();
        };
        boolean established=status==IntegratedQuestionCycle.ExplanationStatus.PROVEN_FORMALLY
                || status==IntegratedQuestionCycle.ExplanationStatus.PROVEN_CONDITIONALLY;
        return new Reply(id,"Question explicative "+id+" ("+status+") :\n"+text,null,!established);
    }
    private Reply renderInvestigation(IntegratedQuestionCycle.InvestigationReport result) {
        String explanation=switch(result.status()) {
            case ALREADY_DETERMINED -> "La proposition est déjà déterminée conditionnellement aux connaissances. Cela ne vérifie pas leur vérité empirique.";
            case PLAN_AVAILABLE -> "Plan RDO proposé (aucune mesure réalisée) : "
                + result.sequence().stream().map(c->c.observation().toString()+" [coût "+c.cost()+"]")
                      .reduce((a,b)->a+" ; "+b).orElse("étape inconnue");
            case NO_PLAN -> "Aucune procédure observable déterminante disponible dans le registre actuel.";
            case INCONSISTENT -> "Connaissances contradictoires : aucune procédure validée.";
            case RESOURCE_LIMIT -> "Budget logique atteint : procédure non établie.";
        };
        return new Reply(result.id(),"Question d'investigation "+result.id()+" ("+result.status()+") :\n"+explanation,null);
    }


    public void declare(String category, String id) { language.declareEntity(category,id); }
    public ControlledFrench language() { return language; }
    /** L'enregistrement lexical ne modifie ni les prémisses ni les obligations FCI. */
    public void registerAdjective(String concept, Set<String> kinds, String masculineSingular,
                                  String feminineSingular, String masculinePlural, String femininePlural) {
        language.lexicon().register(concept,kinds,masculineSingular,feminineSingular,
                masculinePlural,femininePlural);
    }
    public IntegratedQuestionCycle cycle() { return cognitive; }
    /** Sauvegarde/chargement du vocabulaire uniquement : les faits et questions ne changent pas. */
    public void saveLexicon(Path path) throws IOException { language.lexicon().save(path); }
    public void loadLexicon(Path path) throws IOException { language.lexicon().load(path); }
    public String lexicalFingerprint() { return language.lexicon().fingerprint(); }
    public void registerAmbiguousAdjective(String concept, Set<String> kinds,
            String masculineSingular,String feminineSingular,String masculinePlural,String femininePlural) {
        language.lexicon().registerPolysemy(concept,kinds,masculineSingular,feminineSingular,
                masculinePlural,femininePlural);
    }

    public List<String> teach(String phrase) {
        var parsed=parse(phrase,Map.of());
        if(parsed.act()==ControlledFrench.Act.ASK)
            throw new IllegalArgumentException("Utiliser une question, pas une assertion, pour poser Q");
        List<String> ids = new ArrayList<>();
        try {
            for (Formula formula : parsed.formulas())
                ids.add(cognitive.teach(formula,
                        "Énoncé humain, non vérifié (lexique sha256="+language.lexicon().fingerprint()
                        +", relations sha256="+relations.fingerprint()+") : "+phrase));
        } catch (RuntimeException ex) {
            for (String id : ids) cognitive.retract(id);
            throw ex;
        }
        language.commit(parsed);
        // Règle universelle relationnelle : geler aussi le domaine objet,
        // sans introduire d'hypothèses ni de nouveaux faits lors de l'analyse.
        quantifiedRelations.parse(phrase).ifPresent(q->language.freezeKinds(q.frozenKinds()));
        finiteQuantifiers.parse(phrase).ifPresent(q->language.freezeKinds(q.frozenKinds()));
        return List.copyOf(ids);
    }
    public Reply ask(String phrase) {
        Objects.requireNonNull(phrase);
        String normalized=phrase.strip().toLowerCase(Locale.ROOT);
        if(TemporalControlledFrench.looksLikeTemporalQuery(phrase)) {
            try {
                var parsed=TemporalControlledFrench.parseQuery(phrase);
                if(parsed.asOfJournalVersion().isPresent()) {
                    long historicalVersion=parsed.asOfJournalVersion().getAsLong();
                    if(historicalVersion>temporalLedger.version())
                        throw new IllegalArgumentException("Version historique future");
                }
                String id=cognitive.openOwnedTemporal(phrase,parsed.frame(),temporalLedger.version(),
                        parsed.asOfJournalVersion(),dialogueOwner);
                temporalQuestions.add(id); originalQuestions.put(id,phrase);
                return temporalReply(id);
            } catch(IllegalArgumentException|IllegalStateException ex) { return unparsed(phrase,ex.getMessage()); }
        }
        if(normalized.startsWith(WHY)) return askWhy(phrase);
        if(normalized.startsWith(HOW)||normalized.startsWith(HOW_ALT)||normalized.startsWith(HOW_ALT2))
            return askHow(phrase);
        // Ne pas ouvrir d'obligation sur une simple assertion ambiguë.
        String raw=phrase.strip().toLowerCase(Locale.ROOT);
        if(!(raw.endsWith("?") || raw.startsWith("est-ce que ") || raw.startsWith("est ce que ")))
            throw new IllegalArgumentException("Acte interrogatif explicite requis");
        // Traitement explicite d'un fragment ambigu de portée quantificatrice.
        // Les deux formules sont capturées à Q, sans mutation de la base ni du lexique.
        Optional<QuantifierScopeFrench.Readings> scope;
        try { scope=QuantifierScopeFrench.detect(language,phrase); }
        catch(SemanticLexicon.AmbiguousMeaningException lexical) {
            // Une seule obligation même si le choix lexical précède le choix de portée.
            String id=cognitive.openOwnedUnresolved(phrase,lexical.surface(),lexical.candidates(),dialogueOwner);
            Pending p=new Pending(phrase,Map.of(),lexical.surface(),lexical.candidates(),lexicalFingerprint());
            pending.put(id,p);
            originalQuestions.put(id,phrase);
            return clarificationReply(id,p);
        } catch(IllegalArgumentException|IllegalStateException ex) {
            return unparsed(phrase,ex.getMessage());
        }
        if(scope.isPresent()) {
            var readings=scope.orElseThrow();
            String id=cognitive.openOwnedUnresolved(phrase,"portée de ne...pas",List.of("pas_tous","aucun"),dialogueOwner);
            scopePending.put(id,readings);
            Pending p=new Pending(phrase,Map.of(),"portée de ne...pas",List.of("pas_tous","aucun"),readings.lexicalFingerprint());
            pending.put(id,p);
            originalQuestions.put(id,phrase);
            return clarificationReply(id,p);
        }
        try {
            var parsed=parse(phrase,Map.of());
            if(parsed.act()!=ControlledFrench.Act.ASK || parsed.formulas().size()!=1)
                throw new IllegalArgumentException("Acte interrogatif explicite requis");
            var report=cognitive.ask(parsed.formulas().get(0));
            originalQuestions.put(report.id(),phrase);
            return render(report);
        } catch(SemanticLexicon.AmbiguousMeaningException ex) {
            String id=cognitive.openOwnedUnresolved(phrase,ex.surface(),ex.candidates(),dialogueOwner);
            Pending p=new Pending(phrase,Map.of(),ex.surface(),ex.candidates(),lexicalFingerprint());
            pending.put(id,p);
            originalQuestions.put(id,phrase);
            return clarificationReply(id,p);
        } catch(IllegalArgumentException|IllegalStateException ex) {
            return unparsed(phrase,ex.getMessage());
        }
    }
    /** Tentative de clarification typée : un choix hors candidats est REFUSÉ,
     * il ne modifie ni la formule, ni le lexique, ni la FCI initiale.
     * La méthode stricte clarify(...) garde son contrat d'exception pour les API existantes.
     */
    public record ClarificationAttempt(boolean accepted,Reply reply) {}
    private final Map<String,List<String>> rejectedClarifications=new LinkedHashMap<>();
    public List<String> rejectedClarifications(String id) {
        return List.copyOf(rejectedClarifications.getOrDefault(id,List.of()));
    }
    public ClarificationAttempt tryClarify(String id,String meaning) {
        Pending current=pending.get(id);
        if(current==null) throw new IllegalArgumentException("Aucune clarification en attente : "+id);
        if(!lexicalFingerprint().equals(current.lexicalFingerprint()))
            throw new IllegalStateException("Lexique changé : retirer et reposer la question " + id);
        String selected=meaning.strip().toLowerCase(Locale.ROOT);
        if(!current.candidates().contains(selected)) {
            rejectedClarifications.computeIfAbsent(id,k->new ArrayList<>()).add(selected);
            Reply pendingReply=clarificationReply(id,current);
            return new ClarificationAttempt(false,new Reply(id,
                "Choix REFUSÉ « "+selected+" » : sens non proposé.\n"+pendingReply.text(),null,true));
        }
        return new ClarificationAttempt(true,clarify(id,selected));
    }
    /** Clarification d'un Q EXISTANT ; ni nouvel acte interrogatif ni fait. */
    public Reply clarify(String id,String meaning) {
        var current=pending.get(id);
        if(current==null) throw new IllegalArgumentException("Question sans clarification en attente : "+id);
        if(!lexicalFingerprint().equals(current.lexicalFingerprint()))
            throw new IllegalStateException("Lexique modifié depuis l'acte "+id
                    +". Retirer l'obligation (annule "+id+") et reposer la question ; aucun sens n'est supposé.");
        String selected=meaning.strip().toLowerCase(Locale.ROOT);
        if(!current.candidates().contains(selected))
            throw new IllegalArgumentException("Sens non proposé : "+selected+" ; choix : "+current.candidates());
        var scope=scopePending.get(id);
        if(scope!=null) {
            // Clarification de portée : la formule provient de la photographie de Q.
            var report=cognitive.resolveOwnedUnresolved(id,scope.select(selected),dialogueOwner);
            scopePending.remove(id);
            pending.remove(id);
            scopeChosen.put(id,selected.equals("aucun")?scope.labelNone():scope.labelNotAll());
            return render(report);
        }
        Map<String,String> decisions=new LinkedHashMap<>(current.decisions());
        var scopeAfterLexicon=QuantifierScopeFrench.detect(language,current.phrase(),
                Map.of(current.word(),selected));
        if(scopeAfterLexicon.isPresent()) {
            // Première clarification lexicale : la seconde est une clarification
            // de portée du MÊME Q, sous les prémisses déjà photographiées.
            var readings=scopeAfterLexicon.orElseThrow();
            scopePending.put(id,readings);
            Pending next=new Pending(current.phrase(),Map.of(current.word(),selected),
                    "portée de ne...pas",List.of("pas_tous","aucun"),current.lexicalFingerprint());
            cognitive.updateOwnedUnresolved(id,"portée de ne...pas",next.candidates(),dialogueOwner);
            pending.put(id,next);
            return clarificationReply(id,next);
        }
        if(current.phrase().toLowerCase(Locale.ROOT).contains(" et ")) {
            long occurrence=decisions.keySet().stream().filter(k->k.startsWith(current.word()+"#")).count();
            decisions.put(current.word()+"#"+occurrence,selected);
        } else decisions.put(current.word(),selected);
        try {
            var interpreted=parse(current.phrase(),decisions);
            if(interpreted.act()!=ControlledFrench.Act.ASK || interpreted.formulas().size()!=1)
                throw new IllegalStateException("Clarification ne produit pas une question unique");
            var result=cognitive.resolveOwnedUnresolved(id,interpreted.formulas().get(0),dialogueOwner);
            pending.remove(id);
            return render(result);
        } catch(SemanticLexicon.AmbiguousMeaningException ex) {
            // Une autre ambiguïté peut subsister. Même Q, même obligation.
            Pending next=new Pending(current.phrase(),decisions,ex.surface(),ex.candidates(),
                    current.lexicalFingerprint());
            cognitive.updateOwnedUnresolved(id,ex.surface(),ex.candidates(),dialogueOwner);
            pending.put(id,next);
            return clarificationReply(id,next);
        }
    }
    public Reply withdraw(String id) {
        if(temporalQuestions.remove(id)) {
            cognitive.withdrawTemporal(id,"Retrait explicite de question temporelle");
            return new Reply(id,"Question "+id+" retirée ; aucun événement supprimé.",null,false);
        }
        Pending p=pending.get(id);
        if(p==null && !notInterpreted.contains(id))
            throw new IllegalArgumentException("Question sans clarification en attente : "+id);
        cognitive.withdrawOwnedUnresolved(id,"Retrait explicite d'une obligation en attente",dialogueOwner);
        pending.remove(id);
        notInterpreted.remove(id);
        scopePending.remove(id);
        return new Reply(id,"Question "+id+" retirée ; aucune réponse n'est affirmée.",null,false);
    }
    public Reply status(String id) {
        if(temporalQuestions.contains(id)) return temporalReply(id);
        if(notInterpreted.contains(id)) return new Reply(id,
                "Question "+id+" NON INTERPRÉTÉE : "+unparsedReasons.get(id),null);
        if(explanatory.contains(id)) return renderExplanation(cognitive.explanationQuestion(id));
        if(investigatory.contains(id)) return renderInvestigation(cognitive.investigationQuestion(id));
        Pending p=pending.get(id);
        if(p!=null) return clarificationReply(id,p);
        for(var q:cognitive.withdrawnQuestions()) if(q.id().equals(id))
            return new Reply(id,"Question "+id+" retirée : "+q.reason(),null,false);
        return render(cognitive.question(id));
    }
    /** Relecture d'une PREUVE d'un Q existant ; ne crée ni nouveau Q ni fait. */
    public Reply explain(String id) {
        if(pending.containsKey(id))
            return new Reply(id,"Clarification nécessaire avant toute explication.",null);
        var report=cognitive.question(id);
        if(report.status()!=IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && report.status()!=IntegratedQuestionCycle.Status.RESTORED_FORMALLY)
            return new Reply(id,"Aucune réponse établie : explication déductive indisponible ("
                    +report.status()+").",report);
        if(report.certificate().isEmpty())
            return new Reply(id,"Aucun certificat de preuve disponible.",report);
        Formula goal=report.answer().orElseThrow() ? report.question() : Formula.not(report.question());
        Set<Formula> active=new LinkedHashSet<>();
        for(var p:cognitive.premises()) if(p.active()) active.add(p.formula());
        String description=FrenchProofExplanation.render(goal,report.certificate().orElseThrow(),
                active,language.lexicon());
        return new Reply(id,"Explication de "+id+" :\n"+description,report);
    }
    public Reply answerObservation(String id, boolean yes, String source) {
        return render(cognitive.answerObservation(id,yes,source));
    }
    public Reply decline(String id) { return render(cognitive.declineObservation(id)); }
    public void retract(String premiseId) { cognitive.retract(premiseId); }
    public String registerObservable(String expression) {
        var parsed=language.interpret(expression);
        if(parsed.act()!=ControlledFrench.Act.ASSERT || parsed.formulas().size()!=1)
            throw new IllegalArgumentException("Une observation est une proposition atomique positive déclarée");
        Formula f=parsed.formulas().get(0);
        if (!(f instanceof Formula.Atom)) throw new IllegalArgumentException("Observation atomique positive requise");
        cognitive.registerObservable(f,1);
        return f.toString();
    }
    private Reply render(IntegratedQuestionCycle.Report report) {
        String source=originalQuestions.getOrDefault(report.id(), "Question initiale");
        String s = switch(report.status()) {
            case RESTORED_FORMALLY -> report.answer().orElseThrow()
                    ? "Oui. Cette conclusion est un théorème formel sans prémisse externe."
                    : "Non. Sa négation est un théorème formel sans prémisse externe.";
            case RESTORED_CONDITIONALLY, RESTORED_JUSTIFIED -> report.answer().orElseThrow()
                    ? "Oui, sous réserve des informations déclarées."
                    : "Non, sous réserve des informations déclarées.";
            case NEED_INFORMATION -> "La réponse n'est pas déterminée. Information à demander : "
                    +report.requestedObservation().orElseThrow()+". Ceci n'est pas une mesure effectuée.";
            case INCONSISTENT -> "Je ne peux pas répondre : les prémisses sont contradictoires.";
            case RESOURCE_LIMIT -> "Je ne peux pas répondre : budget de raisonnement atteint.";
            case OPEN -> "Je ne sais pas à partir des informations disponibles.";
        };
        String evidence = report.certificate().map(c -> " Statut de justification : "+c.basis()
                    +", version des connaissances "+c.version()+".").orElse("");
        String reading=scopeChosen.containsKey(report.id())
                ? "\nLecture précisée : "+scopeChosen.get(report.id())+"." : "";
        return new Reply(report.id(),"Question : "+source+reading+"\n"+s+evidence+"\n"+report.explanation(),report);
    }
}
