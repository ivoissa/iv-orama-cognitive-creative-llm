package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.Constituent;
import com.ivorama.nlp.core.ConstituentRole;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

public final class SentenceTransformerEngine {
    static final class EmphasisBudget {
        private int remaining;

        EmphasisBudget(int limit) {
            remaining = limit;
        }
    }

    private record EmphasisTarget(SentenceAnalysis sentence, int tokenIndex) {}
    private List<SentenceAnalysis> emphasisDocument = List.of();
    void prepareDocument(List<SentenceAnalysis> analyses) {
        emphasisDocument = List.copyOf(analyses);
        synonymProvider.prepareDocument(analyses);
    }

    private boolean emphasisAdjective(TokenData token) {
        return token.isAdjective() && !morphology.analyze(token).lemma().isBlank();
    }

    /** Sample original adjective occurrences across the entire document, once per seed.
     * Three is included in the full-emphasis branch; above three use floor(N / 3).
     * Selection is independent of lexical success: abstentions never hide a new draw.
     */
    private Set<Integer> emphasisTargets(SentenceAnalysis analysis, long seed) {
        List<SentenceAnalysis> document = emphasisDocument.stream().anyMatch(a -> a == analysis)
                ? emphasisDocument : List.of(analysis);
        var targets = new ArrayList<EmphasisTarget>();
        for (var sentence : document) for (var token : sentence.tokens())
            if (emphasisAdjective(token)) targets.add(new EmphasisTarget(sentence, token.index()));
        int count = targets.size() <= 3 ? targets.size() : targets.size() / 3;
        if (targets.size() > 3) Collections.shuffle(targets, new Random(seed));
        var selected = new HashSet<Integer>();
        for (var target : targets.subList(0, count))
            if (target.sentence() == analysis) selected.add(target.tokenIndex());
        return selected;
    }
    private final SynonymProvider synonymProvider;
    private final ValidationPipeline validationPipeline;
    private final com.ivorama.nlp.core.MorphologicalEngine morphology;
    private final NominalAgreement agreement;

    private static ValidationPipeline defaultPipeline() {
        SynonymCandidateValidator validator = new SynonymCandidateValidator();
        return new ValidationPipeline(List.of(c -> (c.morphologicallyAttested()?validator.isAcceptableWithMorphology(c.original(),c.candidate()):validator.isAcceptable(c.original(), c.candidate(), c.curated()))
                ? Optional.empty() : Optional.of("lexical_candidate_rejected")));
    }

    public SentenceTransformerEngine(SynonymProvider synonymProvider) {
        this(synonymProvider, defaultPipeline());
    }

    public SentenceTransformerEngine(SynonymProvider synonymProvider, ValidationPipeline validationPipeline) {
        this(synonymProvider,validationPipeline,new com.ivorama.nlp.core.LexiconMorphologicalEngine());
    }
    public SentenceTransformerEngine(SynonymProvider synonymProvider,com.ivorama.nlp.core.MorphologicalEngine morphology){this(synonymProvider,defaultPipeline(),morphology);}
    public SentenceTransformerEngine(SynonymProvider synonymProvider, ValidationPipeline validationPipeline,com.ivorama.nlp.core.MorphologicalEngine morphology) {
        this.synonymProvider = java.util.Objects.requireNonNull(synonymProvider, "synonymProvider");
        this.validationPipeline = java.util.Objects.requireNonNull(validationPipeline, "validationPipeline");
        this.morphology=java.util.Objects.requireNonNull(morphology);this.agreement=new NominalAgreement(morphology);
    }

    public TransformationResult transform(SentenceAnalysis analysis, TransformationConfig config) {
        return transform(analysis,config,true);
    }
    TransformationResult transform(SentenceAnalysis analysis, TransformationConfig config,boolean allowGenderChange) {
        return transform(analysis, config, allowGenderChange, new EmphasisBudget(config.emphasisFallbackLimit()));
    }

    TransformationResult transform(SentenceAnalysis analysis, TransformationConfig config,
                                   boolean allowGenderChange, EmphasisBudget budget) {
        java.util.Objects.requireNonNull(analysis, "analysis");
        java.util.Objects.requireNonNull(config, "config");

        SynonymRegister requested = config.requestedRegister();
        SynonymRegister effective = synonymProvider.supportsRegisters() ? requested : SynonymRegister.COURANT;
        List<String> rules = new ArrayList<>();
        List<Replacement> replacements = new ArrayList<>();
        List<LexicalAttempt> attempts=new ArrayList<>();
        Map<Integer, String> words = new HashMap<>();
        analysis.tokens().forEach(t -> words.put(t.index(), t.word()));

        Set<Integer> lexicalUnits = !config.stoiquePhiloMode()
                && (config.replaceNounsEnabled() || config.emphaseColores() || config.emphaseMode())
                ? LexicalUnits.protectedIndices(analysis) : Set.of();

        // STOIQUE a priorité absolue: aucune synonymie, aucun déplacement, aucun Yoda.
        if (!config.stoiquePhiloMode()) {
            if (config.replaceNounsEnabled()) applyNounSynonyms(analysis, effective, words, replacements,attempts,allowGenderChange,config,lexicalUnits);
            if (!replacements.isEmpty()) rules.add("synonymes_noms_1_et_3");
            if (config.emphaseColores()) {
                applyAdjectiveSynonyms(analysis, effective, config.randomSeed(), words, replacements,attempts,allowGenderChange,config,lexicalUnits);
                if (replacements.stream().anyMatch(r -> r.reason().equals("adjectif/emphase")))
                    rules.add("emphase_colores");
            }
        } else {
            rules.add("stoique_philo_mode_override");
        }

        if (config.emphaseMode() && !config.stoiquePhiloMode()) {
            applyEmphasis(analysis, effective, config, words, replacements, attempts, lexicalUnits, budget);
            if (replacements.stream().anyMatch(r -> r.reason().equals("adjectif ajouté/emphase_mode")))
                rules.add("emphase_mode");
        }
        boolean protectedScope=GrammaticalScope.structuralProtected(analysis);
        List<Constituent> ccs = protectedScope?List.of():analysis.circumstantials().stream()
                .filter(SentenceTransformerEngine::isContiguous).filter(c->!ContextualGuard.ambiguousCircumstantial(analysis,c)).filter(c->GrammaticalScope.withinRootClause(analysis,c)&&GrammaticalScope.completeComplement(analysis,c))
                .filter(c -> !c.sourceRelation().startsWith("advcl") || analysis.subject() == null
                        || !analysis.token(analysis.rootTokenIndex()).lemma().equals("être")
                        || c.end() < analysis.subject().start()).toList();
        Constituent firstCcToMove = null;
        Constituent secondCcToMove = null;
        List<Constituent> yodaObjects = List.of();

        if (!config.stoiquePhiloMode()) {
            if (config.joyeuseCirconstanceMode() && !ccs.isEmpty()) {
                // Un complément déjà placé en tête ne doit pas être extrait une seconde fois.
                // Préserver les frontières de propositions : déplacement limité à la
                // proposition contenant le verbe racine.
                List<Constituent> movable = ccs.stream()
                        .filter(cc -> cc.start() > analysis.firstLexicalIndex())
                        .filter(cc -> !analysis.token(analysis.firstLexicalIndex()).pos().equals("CCONJ"))
                        .filter(cc -> sameClause(analysis, cc.start(), analysis.rootTokenIndex()))
                        // An isolated adverb keeps its original scope and position.
                        .filter(cc -> !isIsolatedAdverb(analysis, cc))
                        .toList();
                if (!movable.isEmpty()) firstCcToMove = movable.get(0);
                if (movable.size() >= 2) secondCcToMove = movable.get(1);
                if (firstCcToMove != null) rules.add("joyeuse_circonstance_mode");
            }
            if (!protectedScope&&config.yodaMode() && analysis.subject() != null && !analysis.objects().isEmpty()) {
                // French clitic pronouns stay attached to their verb; move full objects only.
                List<Constituent> movableObjects=analysis.objects().stream()
                        // A bare prepositional argument stays attached to its verb.
                        // Determined groups may include a tokenizer-split partitive.
                        .filter(o -> o.role() == ConstituentRole.DIRECT_OBJECT
                                || !analysis.token(o.start()).pos().equals("ADP")
                                || analysis.tokens().stream().anyMatch(t -> o.contains(t.index()) && t.pos().equals("DET")))
                        .filter(o->!(o.tokenIndices().size()==1&&o.start()<analysis.rootTokenIndex()&&analysis.token(o.start()).pos().equals("PRON"))).toList();
                List<Constituent> safeObjects = movableObjects.stream()
                        .filter(SentenceTransformerEngine::isContiguous)
                        .filter(o -> !overlaps(o, analysis.subject()))
                        .filter(o -> ccs.stream().noneMatch(cc -> overlaps(o, cc)))
                        .filter(o -> sameClause(analysis, o.start(), analysis.rootTokenIndex()))
                        .filter(o->GrammaticalScope.withinRootClause(analysis,o))
                        .toList();
                if (safeObjects.size() == movableObjects.size()
                        && safeObjects.stream().noneMatch(o -> overlaps(o, analysis.subject()))
                        && pairwiseDisjoint(safeObjects)) {
                    yodaObjects = safeObjects;
                    rules.add("yoda_mode");
                }
            }
        }

        if ((config.respirationCoolMode() || config.stoiquePhiloMode()) && !ccs.isEmpty()) {
            rules.add("respiration_cool_mode");
        }

        // Éviter la disparition de tokens en cas de sous-arbres qui se recouvrent.
        if (firstCcToMove != null && secondCcToMove != null && overlaps(firstCcToMove, secondCcToMove))
            secondCcToMove = null;
        final Constituent firstMove = firstCcToMove;
        final Constituent secondMove = secondCcToMove;
        if (firstMove != null && yodaObjects.stream().anyMatch(o -> overlaps(o, firstMove)))
            yodaObjects = List.of();
        if (secondMove != null && yodaObjects.stream().anyMatch(o -> overlaps(o, secondMove)))
            yodaObjects = List.of();
        Set<Integer> removed = new HashSet<>();
        if (firstCcToMove != null) removed.addAll(firstCcToMove.tokenIndices());
        if (secondCcToMove != null) removed.addAll(secondCcToMove.tokenIndices());
        yodaObjects.forEach(o -> removed.addAll(o.tokenIndices()));
        removeAdjacentCommaTokens(analysis, removed);

        // Si un élément est placé avant le début original, le premier mot initial ne doit pas
        // conserver artificiellement sa majuscule de début de phrase (sauf nom propre).
        boolean insertsBeforeOriginalStart = firstCcToMove != null && firstCcToMove.start() > analysis.firstLexicalIndex();
        insertsBeforeOriginalStart |= !yodaObjects.isEmpty() && analysis.subject() != null
                && analysis.subject().start() == analysis.firstLexicalIndex();
        if (insertsBeforeOriginalStart) {
            TokenData initial = analysis.token(analysis.firstLexicalIndex());
            if (canLowerInitial(analysis, initial)) {
                words.put(initial.index(), lowerInitial(words.get(initial.index())));
            }
        }

        Map<Integer,String> surfaceWords=new HashMap<>(words);for(var token:analysis.tokens())surfaceWords.put(token.index(),SurfaceTokens.word(analysis,token,words));
        List<String> output = new ArrayList<>();
        Set<Integer> policyCommaPositions=new HashSet<>();
        if (firstCcToMove != null) {
            appendConstituent(output, firstCcToMove, surfaceWords);
            output.add(",");
        }

        int subjectStart = analysis.subject() == null ? -1 : analysis.subject().start();
        Set<Integer> yodaStartsEmitted = new HashSet<>();
        Map<Integer, Constituent> stationaryCcStarts = new HashMap<>();
        Map<Integer, Constituent> stationaryCcEnds = new HashMap<>();
        if (config.respirationCoolMode() || config.stoiquePhiloMode()) {
            for (Constituent cc : ccs) {
                if (cc == firstCcToMove || cc == secondCcToMove || isIsolatedAdverb(analysis, cc)) continue;
                stationaryCcStarts.put(cc.start(), cc);
                stationaryCcEnds.put(cc.end(), cc);
            }
        }

        for (TokenData token : analysis.tokens()) {
            int i = token.index();
            if (removed.contains(i)) continue;

            if (i == subjectStart && !yodaObjects.isEmpty() && yodaStartsEmitted.add(subjectStart)) {
                for (Constituent object : yodaObjects) appendConstituent(output, object, surfaceWords);
            }

            Constituent startCc = stationaryCcStarts.get(i);
            if (startCc != null && startCc.start() > analysis.firstLexicalIndex()) {policyCommaPositions.add(output.size());output.add(",");}

            output.add(surfaceWords.get(i));

            Constituent endCc = stationaryCcEnds.get(i);
            if (endCc != null && endCc.end() < analysis.lastLexicalIndex()) {policyCommaPositions.add(output.size());output.add(",");}

            if (i == analysis.rootTokenIndex() && secondCcToMove != null) {
                output.add(",");
                appendConstituent(output, secondCcToMove, surfaceWords);
                if (hasLexicalContentAfter(analysis, i, removed)) output.add(",");
            }
        }

        boolean reordered=firstCcToMove!=null||secondCcToMove!=null||!yodaObjects.isEmpty();
        boolean punctuation=(config.respirationCoolMode()||config.stoiquePhiloMode())&&!ccs.isEmpty();
        var withoutPolicy=java.util.stream.IntStream.range(0,output.size()).filter(i->!policyCommaPositions.contains(i)).mapToObj(output::get).toList();
        boolean infer=analysis.tokens().stream().anyMatch(t->t.beginOffset()<0);
        boolean commaEffect=!SentenceRenderer.render(output,infer).equals(SentenceRenderer.render(withoutPolicy,infer));
        if(yodaObjects.isEmpty())rules.remove("yoda_mode");
        String transformed = reordered || punctuation
                ? new TextRewriter(List.of(sentence -> List.of(new TextEdit(0, sentence.originalText().length(),
                    SentenceRenderer.render(output, infer), 0, "structure", Optional.empty())))).rewrite(analysis)
                : TokenTextEditor.apply(analysis, words);
        int nounChanges=(int)replacements.stream().filter(r->(r.reason().contains("nom/groupe nominal") || r.reason().equals("nom alternatif"))).count();
        int adjectiveChanges=(int)replacements.stream().filter(r->r.reason().equals("adjectif/emphase")).count();
        int additions=(int)replacements.stream().filter(r->r.reason().equals("adjectif ajouté/emphase_mode")).count();
        var executions=List.of(
          new RuleExecution("emphase_mode",config.emphaseMode(),!config.emphaseMode()?"DISABLED":config.stoiquePhiloMode()?"OVERRIDDEN":additions>0?"APPLIED":"ABSTAINED","DOCUMENT_ADJECTIVES_ALL_UP_TO_3_ELSE_FLOOR_THIRD_SEE_EMPHASIS_ATTEMPTS",additions),
          new RuleExecution("nouns_1_and_3",config.replaceNounsEnabled(),!config.replaceNounsEnabled()?"DISABLED":config.stoiquePhiloMode()?"OVERRIDDEN":nounChanges>0?"APPLIED":"ABSTAINED","SEE_LEXICAL_ATTEMPTS",nounChanges),
          new RuleExecution("adjectives",config.emphaseColores(),!config.emphaseColores()?"DISABLED":config.stoiquePhiloMode()?"OVERRIDDEN":adjectiveChanges>0?"APPLIED":"ABSTAINED","SEE_LEXICAL_ATTEMPTS",adjectiveChanges),
          new RuleExecution("yoda",config.yodaMode(),!config.yodaMode()?"DISABLED":config.stoiquePhiloMode()?"OVERRIDDEN":!yodaObjects.isEmpty()?"APPLIED":"NOT_APPLICABLE",protectedScope?GrammaticalScope.blockingReason(analysis):"BOUNDED_ROOT_CLAUSE",yodaObjects.size()),
          new RuleExecution("circumstantials",config.joyeuseCirconstanceMode(),!config.joyeuseCirconstanceMode()?"DISABLED":config.stoiquePhiloMode()?"OVERRIDDEN":firstCcToMove!=null?"APPLIED":"NOT_APPLICABLE","ROOT_CLAUSE_AND_COMPLETE_COMPLEMENTS",(firstCcToMove==null?0:1)+(secondCcToMove==null?0:1)),
          new RuleExecution("commas",config.respirationCoolMode()||config.stoiquePhiloMode(),!(config.respirationCoolMode()||config.stoiquePhiloMode())?"DISABLED":commaEffect?"APPLIED":ccs.isEmpty()?"NOT_APPLICABLE":"ALREADY_SATISFIED","LEGACY_APPLIED_RULES_DESCRIBES_APPLICABILITY; THIS_STATUS_DESCRIBES_EFFECT",commaEffect?1:0),
          new RuleExecution("stoic",config.stoiquePhiloMode(),config.stoiquePhiloMode()?"OVERRIDE_ACTIVE":"DISABLED","LEXICAL_AND_MOVEMENT_MODES_OVERRIDDEN",0),
          new RuleExecution("register",true,requested==effective?"SELECTED":"FALLBACK",requested+" -> "+effective,0));
        return new TransformationResult(
                analysis.originalText(),
                transformed,
                requested,
                effective,
                List.copyOf(new LinkedHashSet<>(rules)),
                List.copyOf(replacements),List.copyOf(attempts),executions);
    }

    private boolean hasAdjectiveComplement(SentenceAnalysis analysis, TokenData adjective) {
        return analysis.tokens().stream()
                .filter(token -> token.headIndex() == adjective.index())
                .map(TokenData::dependency)
                .anyMatch(relation -> relation.equals("obl") || relation.startsWith("obl:")
                        || relation.equals("nmod") || relation.startsWith("nmod:")
                        || relation.equals("xcomp") || relation.equals("ccomp"));
    }

    private void applyEmphasis(SentenceAnalysis analysis, SynonymRegister register, TransformationConfig config,
                               Map<Integer, String> words, List<Replacement> replacements,
                               List<LexicalAttempt> attempts, Set<Integer> lexicalUnits, EmphasisBudget budget) {
        Set<Integer> selected = emphasisTargets(analysis, config.randomSeed());
        // Keep a pre-addition snapshot: a preceding insertion must not alter agreement
        // analysis for another selected adjective in the same group.
        Map<Integer, String> base = new HashMap<>(words);
        for (var token : analysis.tokens()) {
            if (!emphasisAdjective(token)) continue;
            String lemma = morphology.analyze(token).lemma();
            if (!selected.contains(token.index())) {
                attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(),
                        "NOT_SELECTED", "EMPHASIS_DOCUMENT_SAMPLE", List.of(), "", List.of()));
                continue;
            }
            if (lexicalUnits.contains(token.index()) || LexicalSafety.isProtectedExpression(analysis, token)
                    || GrammaticalScope.quoted(analysis, token) || GrammaticalScope.operator(token)
                    || GrammaticalScope.localOperator(analysis, token)) {
                attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(),
                        "PROTECTED", "EMPHASIS_GRAMMATICAL_SCOPE", List.of(), "", List.of()));
                continue;
            }
            if (hasAdjectiveComplement(analysis, token)) {
                attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(),
                        "PROTECTED", "EMPHASIS_ADJECTIVE_COMPLEMENT", List.of(), "", List.of()));
                continue;
            }
            var inventory = new LinkedHashSet<String>(synonymProvider.candidates(token, register, analysis));
            inventory.addAll(synonymProvider.inventory(token, register, analysis));
            var configured = token.dependency().startsWith("amod")
                    && token.index() > token.headIndex()
                    ? config.nounEmphasis().stream()
                        .filter(adjective -> adjective.position() == NounEmphasisRule.Position.AFTER_NOUN)
                        .map(NounEmphasisRule.Adjective::lemma).collect(java.util.stream.Collectors.toSet())
                    : Set.<String>of();
            inventory.addAll(configured.stream().sorted().toList());
            var checks = new ArrayList<LexicalAttempt.Check>();
            String chosen = "";
            // Adjective vocabulary and proximity come only from the configured provider.
            // No word pairs or emergency adjectives are embedded in the algorithm.
            for (String candidate : inventory) {
                var decision = synonymProvider.assess(token, register, candidate, analysis);
                boolean fallback = !decision.allowed() && configured.contains(candidate);
                String reason = decision.allowed() || fallback && budget.remaining > 0 ? "" : decision.reason();
                if(reason.isEmpty()&&!synonymProvider.compatibleAddition(token,base.get(token.index()),candidate,analysis))reason="EMPHASIS_COORDINATION_CONFLICT";
                String form = "";
                if (reason.isEmpty()) {
                    var evaluation = agreement.evaluate(analysis, token, candidate, base, false);
                    if (evaluation.plan() == null) reason = evaluation.reason();
                    else form = evaluation.plan().words().get(token.index());
                }
                if(reason.isEmpty()&&ContextualGuard.introducesDuplicate(analysis,token,candidate,base,morphology))reason="INTRODUCED_LEMMA_DUPLICATE";
                if (reason.isEmpty() && (form == null || !form.matches("[\\p{L}'’-]+")))
                    reason = "EMPHASIS_SINGLE_ADJECTIVE_REQUIRED";
                if (reason.isEmpty()) {
                    String addedLemma = morphology.analyze(new TokenData(token.index(), form, "ADJ")).lemma();
                    // Avoid adding the original, a synonym already present nearby, or
                    // an inflection that the form dictionary cannot identify as ADJ.
                    if (addedLemma.isBlank() || addedLemma.equals(lemma)
                            || analysis.tokens().stream().filter(TokenData::isAdjective)
                                .filter(t -> Math.abs(t.index() - token.index()) <= 4)
                                .anyMatch(t -> addedLemma.equals(morphology.analyze(
                                    new TokenData(t.index(), base.get(t.index()), "ADJ")).lemma())))
                        reason = "EMPHASIS_DUPLICATE_OR_UNATTESTED_ADJECTIVE";
                }
                if (!reason.isEmpty()) {
                    checks.add(new LexicalAttempt.Check(candidate, "REJECTED", reason,
                            decision.score(), decision.sense(), decision.evidence()));
                    continue;
                }
                String before = words.get(token.index());
                words.put(token.index(), before + " et " + form.toLowerCase(java.util.Locale.ROOT));
                replacements.add(new Replacement(token.index(), before, words.get(token.index()),
                        "adjectif ajouté/emphase_mode"));
                if (fallback) budget.remaining--;
                checks.add(new LexicalAttempt.Check(candidate, "APPLIED", fallback
                        ? "CONFIGURED_EMPHASIS_ATTESTED_AGREEMENT" : "EMPHASIS_ADDITION_ATTESTED_AGREEMENT",
                        decision.score(), decision.sense(), decision.evidence()));
                chosen = form;
                break;
            }
            attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(),
                    chosen.isEmpty() ? "ABSTAINED" : "APPLIED",
                    chosen.isEmpty() ? "EMPHASIS_NO_ADMISSIBLE_CANDIDATE" : "EMPHASIS_ADDITION",
                    List.copyOf(inventory), chosen, checks));
        }
    }

    private void applyNounSynonyms(SentenceAnalysis analysis, SynonymRegister register,
                                   Map<Integer, String> words, List<Replacement> replacements,List<LexicalAttempt> attempts,boolean allowGenderChange, TransformationConfig config, Set<Integer> lexicalUnits) {
        List<TokenData> nouns = analysis.tokens().stream().filter(TokenData::isNoun).filter(t->!Set.of("PROPN","NPP").contains(t.pos())).toList();
        int initial = replacements.size();
        if (!nouns.isEmpty()) replace(nouns.get(0), register, "1er nom/groupe nominal", analysis, words, replacements,attempts,allowGenderChange,config,lexicalUnits);
        if (nouns.size() >= 3) replace(nouns.get(2), register, "3e nom/groupe nominal", analysis, words, replacements,attempts,allowGenderChange,config,lexicalUnits);
        if (config.directDictionary() && config.nounSubstitutionRate() == 100
                && nouns.size() >= 2) {
            int applied = (int) replacements.subList(initial, replacements.size()).stream()
                    .filter(edit -> edit.reason().contains("nom/groupe nominal")).count();
            for (int index = 1; index < nouns.size() && applied < 2; index++) {
                if (index == 2) continue;
                int before = replacements.size();
                replace(nouns.get(index), register, "nom alternatif", analysis, words, replacements,
                        attempts, allowGenderChange, config, lexicalUnits);
                if (replacements.size() > before) applied++;
            }
        }
    }

    private void applyAdjectiveSynonyms(SentenceAnalysis analysis, SynonymRegister register, long seed,
                                        Map<Integer, String> words, List<Replacement> replacements,List<LexicalAttempt> attempts,boolean allowGenderChange, TransformationConfig config, Set<Integer> lexicalUnits) {
        List<TokenData> adjectives = new ArrayList<>(analysis.tokens().stream().filter(TokenData::isAdjective).toList());
        if (adjectives.isEmpty()) return;

        // Interprétation cohérente de la règle contradictoire du prompt:
        // 1 à 3 adjectifs => tous; plus de 3 => moitié (arrondi supérieur), tirée pseudo-aléatoirement.
        List<TokenData> selected;
        if (adjectives.size() <= 3) {
            selected = adjectives;
        } else {
            Collections.shuffle(adjectives, new Random(seed));
            int count = (adjectives.size() + 1) / 2;
            selected = adjectives.subList(0, count);
        }
        selected.stream().sorted(java.util.Comparator.comparingInt(TokenData::index))
                .forEach(t -> replace(t, register, "adjectif/emphase", analysis, words, replacements,attempts,allowGenderChange,config,lexicalUnits));
    }

    private boolean hasConflictingNominalCategory(SentenceAnalysis analysis, TokenData token) {
        if (!token.isNoun()) return false;
        String relation = token.dependency().split(":", 2)[0];
        if (Set.of("amod", "advmod", "discourse").contains(relation)) return true;
        if (isDetachedNominalBeforeApposition(analysis, token)) return true;

        // CoreNLP can parse an adjective/noun homograph and its neighbour as two nouns.
        // Protect the homograph in either orientation of the adjacent apposition.
        if (morphology.analyze(new TokenData(token.index(), token.word(), "ADJ")).lemma().isBlank()) return false;
        if (token.dependency().equals("appos") && token.headIndex() == token.index() - 1
                && analysis.findToken(token.headIndex()).filter(TokenData::isNoun).isPresent()) return true;
        return analysis.findToken(token.index() + 1).filter(TokenData::isNoun)
                .filter(next -> !Set.of("PROPN", "NPP").contains(next.pos()))
                .filter(next -> next.headIndex() == token.index() && next.dependency().equals("appos"))
                .isPresent();
    }

    private static boolean isFixedUnitMember(SentenceAnalysis analysis, TokenData token) {
        return token.dependency().equals("fixed") || token.dependency().startsWith("fixed:")
                || !analysis.dependentsWithRelationPrefix(token.index(), "fixed").isEmpty();
    }

    private static boolean isDetachedNominalBeforeApposition(SentenceAnalysis analysis, TokenData token) {
        if (token.index() != analysis.firstLexicalIndex()
                && analysis.findToken(token.index() - 1).filter(TokenData::isPunctuation).isEmpty()) return false;
        if (analysis.dependentsOf(token.index()).stream()
                .anyMatch(t -> Set.of("det", "case").contains(t.dependency()))) return false;
        return analysis.findToken(token.index() + 1)
                .filter(next -> next.pos().equals("DET"))
                .flatMap(next -> analysis.findToken(next.headIndex()))
                .filter(head -> head.isNoun() && head.headIndex() == token.index()
                        && head.dependency().equals("appos"))
                .isPresent();
    }

    private void replace(TokenData token, SynonymRegister register, String reason, SentenceAnalysis analysis,
                         Map<Integer, String> words, List<Replacement> replacements,List<LexicalAttempt> attempts,boolean allowGenderChange, TransformationConfig config, Set<Integer> lexicalUnits) {
        boolean directDictionary = config.directDictionary();
        boolean semanticFilters = !directDictionary || config.candidateRejectionsEnabled();
        String lemma=morphology.analyze(token).lemma();
        if (lexicalUnits.contains(token.index()) || isFixedUnitMember(analysis, token)) {
            attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(), "PROTECTED",
                    "PROTECTED_LEXICAL_UNIT", List.of(), "", List.of()));
            return;
        }
        if (hasConflictingNominalCategory(analysis, token)) {
            attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(), "PROTECTED",
                    "NOMINAL_CATEGORY_CONFLICT", List.of(), "", List.of()));
            return;
        }
        // Preserve repeated nominal references rather than silently changing only one occurrence.
        if(semanticFilters&&token.isNoun()&&!lemma.isBlank()&&emphasisDocument.stream().flatMap(a->a.tokens().stream()).filter(TokenData::isNoun).filter(t->lemma.equals(morphology.analyze(t).lemma())).count()>1){
            attempts.add(new LexicalAttempt(token.index(),token.word(),lemma,token.pos(),"PROTECTED","REPEATED_NOMINAL_REFERENCE",List.of(),"",List.of()));return;
        }
        var lookups=new ArrayList<TokenData>();lookups.add(token);
        if(!lemma.isBlank()&&!lemma.equalsIgnoreCase(token.word()))lookups.add(new TokenData(token.index(),lemma,token.pos(),token.beginOffset(),token.endOffset(),token.lemma(),token.gender(),token.number(),token.headIndex(),token.dependency()));
        var inventory=new LinkedHashSet<String>();var candidates=new LinkedHashSet<String>();
        for (var lookup : lookups) {
            inventory.addAll(directDictionary ? synonymProvider.dictionaryOrder(lookup, register, analysis)
                    : synonymProvider.inventory(lookup, register, analysis));
            if (!directDictionary) candidates.addAll(synonymProvider.candidates(lookup, register, analysis));
        }
        if(LexicalSafety.isProtectedExpression(analysis,token)||GrammaticalScope.quoted(analysis,token)||GrammaticalScope.operator(token)||(semanticFilters&&GrammaticalScope.localOperator(analysis,token))){
            attempts.add(new LexicalAttempt(token.index(),token.word(),lemma,token.pos(),"PROTECTED","GRAMMATICAL_SCOPE",List.copyOf(inventory),"",List.of()));return;
        }
        var checks = new ArrayList<LexicalAttempt.Check>();
        var ordered = new LinkedHashSet<String>(candidates);
        ordered.addAll(inventory);
        for (String candidate : ordered) {
            var decision = assessCandidate(lookups, register, candidate, analysis, directDictionary, semanticFilters);
            if (!decision.allowed()) {
                checks.add(new LexicalAttempt.Check(candidate,"REJECTED",decision.reason(),decision.score(),decision.sense(),decision.evidence()));
                continue;
            }
            if (token.isAdjective() && decision.reason().equals("DIRECT_ROBERT_LEXICAL_EVIDENCE")
                    && hasAdjectiveComplement(analysis, token)) {
                checks.add(new LexicalAttempt.Check(candidate, "REJECTED", "ADJECTIVE_COMPLEMENT_PROTECTED",
                        decision.score(), decision.sense(), decision.evidence()));
                continue;
            }
            var evaluation=agreement.evaluate(analysis,token,candidate,words,allowGenderChange);
            if(evaluation.plan()==null){checks.add(new LexicalAttempt.Check(candidate,"REJECTED",evaluation.reason(),decision.score(),decision.sense(),decision.evidence()));continue;}
            var plan=evaluation.plan();String surface=plan.words().get(token.index());
            if (plan.words().entrySet().stream().anyMatch(edit -> !java.util.Objects.equals(words.get(edit.getKey()), edit.getValue())
                    && (lexicalUnits.contains(edit.getKey()) || isFixedUnitMember(analysis, analysis.token(edit.getKey()))))) {
                checks.add(new LexicalAttempt.Check(candidate, "REJECTED", "PROTECTED_LEXICAL_UNIT",
                        decision.score(), decision.sense(), decision.evidence()));
                continue;
            }
            if(semanticFilters&&ContextualGuard.introducesDuplicate(analysis,token,candidate,words,morphology)){checks.add(new LexicalAttempt.Check(candidate,"REJECTED","INTRODUCED_LEMMA_DUPLICATE",decision.score(),decision.sense(),decision.evidence()));continue;}
            var validation=new CandidateValidation(token,surface,synonymProvider.isCuratedCandidate(token,register,candidate),true);
            var rejected = directDictionary ? Optional.<String>empty() : validationPipeline.rejectReason(validation);
            if(!new SynonymCandidateValidator().isAcceptableWithMorphology(token,surface)||rejected.isPresent()){
                checks.add(new LexicalAttempt.Check(candidate,"REJECTED",rejected.orElse("INVALID_OR_IDENTICAL_SURFACE"),decision.score(),decision.sense(),decision.evidence()));continue;
            }
            int rate = token.isNoun() ? config.nounSubstitutionRate() : config.adjectiveSubstitutionRate();
            long seed = config.randomSeed() ^ ((long) analysis.originalText().hashCode() << 32)
                    ^ ((long) analysis.beginOffset() << 16) ^ token.index();
            if (rate < 100 && new SplittableRandom(seed).nextInt(100) >= rate) {
                checks.add(new LexicalAttempt.Check(candidate, "SKIPPED", "LITE_SUBSTITUTION_RATE",
                        decision.score(), decision.sense(), decision.evidence()));
                attempts.add(new LexicalAttempt(token.index(), token.word(), lemma, token.pos(), "SKIPPED",
                        "LITE_SUBSTITUTION_RATE", List.copyOf(inventory), "", checks));
                return;
            }
            for(var edit:plan.words().entrySet())if(!words.get(edit.getKey()).equals(edit.getValue())){String before=words.put(edit.getKey(),edit.getValue());replacements.add(new Replacement(edit.getKey(),before,edit.getValue(),edit.getKey()==token.index()?reason:"accord grammatical attesté"));}
            String evidence = directDictionary ? "Classement contextuel : " + decision.reason() + ". " + decision.evidence()
                    : decision.evidence();
            checks.add(new LexicalAttempt.Check(candidate,"APPLIED",plan.evidence(),decision.score(),decision.sense(),evidence));
            attempts.add(new LexicalAttempt(token.index(),token.word(),lemma,token.pos(),"APPLIED",directDictionary ? "DIRECT_DICTIONARY" : "SEMANTICS_AND_MORPHOLOGY",List.copyOf(inventory),surface,checks));return;
        }
        attempts.add(new LexicalAttempt(token.index(),token.word(),lemma,token.pos(),"ABSTAINED",inventory.isEmpty()?"NO_LEXICAL_CANDIDATE":"NO_ADMISSIBLE_CANDIDATE",List.copyOf(inventory),"",checks));
    }

    private CandidateDecision assessCandidate(List<TokenData> lookups, SynonymRegister register,
                                              String candidate, SentenceAnalysis analysis, boolean direct, boolean semanticFilters) {
        CandidateDecision best = CandidateDecision.reject("NO_SEMANTIC_AUTHORIZATION");
        for (var lookup : lookups) {
            var decision = !semanticFilters ? synonymProvider.assessUnchecked(lookup, register, candidate, analysis)
                    : direct ? synonymProvider.assessDirect(lookup, register, candidate, analysis)
                    : synonymProvider.assess(lookup, register, candidate, analysis);
            if (decision.allowed()) return decision;
            if (decision.score() > best.score() || best.reason().equals("NO_SEMANTIC_AUTHORIZATION")) best = decision;
        }
        return best;
    }

    /** Ne pas transporter un constituant à travers un séparateur de propositions. */
    private static boolean isIsolatedAdverb(SentenceAnalysis analysis, Constituent constituent) {
        return constituent.tokenIndices().size() == 1 && analysis.token(constituent.start()).pos().startsWith("ADV");
    }

    private static boolean sameClause(SentenceAnalysis analysis, int first, int second) {
        int from = Math.min(first, second), to = Math.max(first, second);
        return analysis.tokens().stream().noneMatch(t -> t.index() > from && t.index() < to
                && (";".equals(t.word()) || ":".equals(t.word())));
    }

    private static boolean isContiguous(Constituent c) {
        return c.tokenIndices().size() == c.end() - c.start() + 1;
    }

    private static boolean overlaps(Constituent a, Constituent b) {
        return a.tokenIndices().stream().anyMatch(b.tokenIndices()::contains);
    }

    private static boolean pairwiseDisjoint(List<Constituent> constituents) {
        for (int i = 0; i < constituents.size(); i++)
            for (int j = i + 1; j < constituents.size(); j++)
                if (overlaps(constituents.get(i), constituents.get(j))) return false;
        return true;
    }

    private static void appendConstituent(List<String> output, Constituent constituent, Map<Integer, String> words) {
        for (int index : constituent.tokenIndices()) {
            String word = words.get(index);
            if (word != null && !word.matches("[,.]+")) output.add(word);
        }
    }

    private static void removeAdjacentCommaTokens(SentenceAnalysis analysis, Set<Integer> removed) {
        Set<Integer> extra = new HashSet<>();
        for (int index : List.copyOf(removed)) {
            for (int neighbor : new int[]{index - 1, index + 1}) {
                if (neighbor < 1) continue;
                try {
                    TokenData token = analysis.token(neighbor);
                    if (",".equals(token.word()) && removableBoundaryComma(analysis, removed, neighbor)) extra.add(neighbor);
                } catch (IllegalArgumentException ignored) {
                    // bord de phrase
                }
            }
        }
        removed.addAll(extra);
    }


    // A comma introducing a surviving detached element must not disappear
    // merely because the preceding constituent was moved.
    private static boolean removableBoundaryComma(SentenceAnalysis a, Set<Integer> removed, int comma) {
        if (removed.contains(comma + 1)) return true;
        if (!removed.contains(comma - 1)) return false;
        return a.tokens().stream().filter(t -> t.index() > comma)
                .noneMatch(t -> !removed.contains(t.index()) && !t.isPunctuation());
    }

    private static boolean canLowerInitial(SentenceAnalysis analysis, TokenData initial) {
        if (!Set.of("PROPN", "NPP").contains(initial.pos())) return true;

        // CoreNLP may tag an elided subject clitic as a proper name.
        if (!initial.dependency().equals("nsubj") || initial.beginOffset() < 0) return false;
        String clitic = initial.word().toLowerCase(java.util.Locale.ROOT);
        if (!clitic.equals("j’") && !clitic.equals("j'")) return false;
        return analysis.tokens().stream().anyMatch(head -> head.index() == initial.headIndex()
                && head.beginOffset() == initial.endOffset()
                && (head.pos().startsWith("V") || head.pos().equals("AUX")));
    }

    private static String lowerInitial(String word) {
        if (word == null || word.isEmpty()) return word;
        int cp = word.codePointAt(0);
        int lower = Character.toLowerCase(cp);
        return new StringBuilder().appendCodePoint(lower).append(word.substring(Character.charCount(cp))).toString();
    }

    private static boolean hasLexicalContentAfter(SentenceAnalysis analysis, int index, Set<Integer> removed) {
        return analysis.tokens().stream()
                .anyMatch(t -> t.index() > index && !removed.contains(t.index()) && !t.isPunctuation());
    }
}
