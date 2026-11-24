package fr.ivorama.cognitive;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** IVOC-FND-006: RSI typée, distincte de la FCI et du langage propositionnel. */
public sealed interface SemanticIntermediateRepresentation
        permits SemanticIntermediateRepresentation.LogicProblemFrame,
                SemanticIntermediateRepresentation.RelationFrame,
                SemanticIntermediateRepresentation.EventFrame,
                SemanticIntermediateRepresentation.TemporalQuestionFrame,
                SemanticIntermediateRepresentation.CausalClaim,
                SemanticIntermediateRepresentation.CausalQuestionFrame {
    /** Problème structuré fini, avant compilation vers le langage propositionnel existant. */
    record LogicProblemFrame(StructuredLogic.Problem problem) implements SemanticIntermediateRepresentation {
        public LogicProblemFrame {Objects.requireNonNull(problem);}
    }
    record EntityRef(String kind, String name) {
        public EntityRef {
            kind = normalized(kind); name = normalized(name);
            if (!kind.matches("[a-z]+") || !name.matches("[a-z0-9_]+"))
                throw new IllegalArgumentException("Entité RSI invalide");
        }
    }
    /** Relation logique binaire typée; PAS une observation indépendante. */
    record RelationFrame(String relation, EntityRef subject, EntityRef object, boolean negated)
            implements SemanticIntermediateRepresentation {
        public RelationFrame {
            relation=normalized(relation);
            if(!relation.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Concept relationnel invalide");
            Objects.requireNonNull(subject); Objects.requireNonNull(object);
        }
    }
    enum EventKind { VALIDATION, ANNULATION_VALIDATION, ARCHIVAGE }
    enum QuestionKind { VALIDATION_ACTIVE, ARCHIVAGE_CONSTATE }
    /** Le moment de l'événement n'est PAS son instant de connaissance. */
    record EventFrame(String id, EntityRef entity, EventKind kind,
                      long eventTime, long knownTime, String source,
                      Optional<String> targetId) implements SemanticIntermediateRepresentation {
        public EventFrame {
            id = normalized(id);
            Objects.requireNonNull(entity); Objects.requireNonNull(kind);
            source = Objects.requireNonNull(source).strip();
            Objects.requireNonNull(targetId);
            if (!id.matches("e[a-z0-9_]+") || source.isEmpty()
                    || eventTime < 0 || knownTime < eventTime)
                throw new IllegalArgumentException("Identifiant, temps ou source RSI invalide");
            targetId=targetId.map(SemanticIntermediateRepresentation::normalized);
            if ((kind == EventKind.ANNULATION_VALIDATION) != targetId.isPresent())
                throw new IllegalArgumentException("Annulation exige une validation cible ; autres événements sans cible");
        }
    }
    record TemporalQuestionFrame(EntityRef entity, QuestionKind kind,
                                 long atTime, Optional<String> validationId)
            implements SemanticIntermediateRepresentation {
        public TemporalQuestionFrame {
            Objects.requireNonNull(entity); Objects.requireNonNull(kind);
            Objects.requireNonNull(validationId);
            validationId=validationId.map(SemanticIntermediateRepresentation::normalized);
            if (atTime < 0 || (kind == QuestionKind.VALIDATION_ACTIVE) != validationId.isPresent())
                throw new IllegalArgumentException("Question temporelle non déterminée");
        }
    }
    /** Assertion sur une relation causale, jamais une causalite demontree. */
    record CausalClaim(String id, String causeEventId, String effectEventId,
                       long declaredKnownTime, String source)
            implements SemanticIntermediateRepresentation {
        public CausalClaim {
            id=normalized(id); causeEventId=normalized(causeEventId);
            effectEventId=normalized(effectEventId);
            source=Objects.requireNonNull(source).strip();
            if (!id.matches("c[a-z0-9_]+") || !causeEventId.matches("e[a-z0-9_]+")
                    || !effectEventId.matches("e[a-z0-9_]+")
                    || causeEventId.equals(effectEventId) || declaredKnownTime<0 || source.isBlank())
                throw new IllegalArgumentException("Hypothese causale RSI invalide");
        }
    }
    record CausalQuestionFrame(String causeEventId, String effectEventId)
            implements SemanticIntermediateRepresentation {
        public CausalQuestionFrame {
            causeEventId=normalized(causeEventId); effectEventId=normalized(effectEventId);
            if(!causeEventId.matches("e[a-z0-9_]+") || !effectEventId.matches("e[a-z0-9_]+")
                    || causeEventId.equals(effectEventId))
                throw new IllegalArgumentException("Question causale RSI invalide");
        }
    }
    private static String normalized(String text) {
        return Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
    }
}
