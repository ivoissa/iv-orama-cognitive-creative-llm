package fr.ivorama.cognitive;

import java.util.*;

/**
 * Sens explicite d'une question : cellules d'une partition des valuations.
 * Une partition est donnée par le demandeur; elle ne doit PAS être inférée d'un « ou » ambigu.
 * Ce diagnostic ne crée aucune faille : seul l'acte ask de la session le fait.
 */
public final class QuestionPartition {
    private QuestionPartition() {}
    public enum Status { OPEN, DECIDED, INCONSISTENT, RESOURCE_LIMIT }
    public record Cell(String name, Formula condition) {
        public Cell {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Cell name required");
            Objects.requireNonNull(condition);
        }
    }
    public record Definition(List<Cell> cells) {
        public Definition {
            cells = List.copyOf(cells);
            if (cells.size() < 2) throw new IllegalArgumentException("At least two cells required");
            if (cells.stream().map(Cell::name).distinct().count() != cells.size())
                throw new IllegalArgumentException("Duplicate cell names");
        }
    }
    public record Assessment(Status status, Optional<Cell> selected, Set<String> compatible,
                             String explanation) {
        public Assessment {
            Objects.requireNonNull(status); Objects.requireNonNull(selected);
            compatible = Set.copyOf(compatible);
            Objects.requireNonNull(explanation);
        }
    }
    /** Validates mutual exclusion and exhaustiveness over ALL valuations, independently of K. */
    public static boolean valid(Definition definition, ModelExplorer.Budget budget) {
        Objects.requireNonNull(definition); Objects.requireNonNull(budget);
        List<Formula> extra = definition.cells().stream().map(Cell::condition).toList();
        var all = ModelExplorer.analyzeFull(List.of(), Formula.Bottom.INSTANCE, extra, budget);
        if (all.status() == ModelExplorer.Status.RESOURCE_LIMIT)
            throw new IllegalStateException("Partition validity outside calculation budget");
        for (var world : all.possibleWorlds()) {
            int count=0;
            for (Cell cell : definition.cells()) if (world.satisfies(cell.condition())) count++;
            if (count != 1) return false;
        }
        return true;
    }
    public static Assessment evaluate(Definition definition, Collection<Formula> context,
                                      ModelExplorer.Budget budget) {
        if (!valid(definition, budget)) throw new IllegalArgumentException("Cells do not form a partition");
        List<Formula> extra = definition.cells().stream().map(Cell::condition).toList();
        var model = ModelExplorer.analyzeFull(context, Formula.Bottom.INSTANCE, extra, budget);
        if (model.status() == ModelExplorer.Status.RESOURCE_LIMIT)
            return new Assessment(Status.RESOURCE_LIMIT, Optional.empty(), Set.of(), "Budget exceeded");
        if (model.status() == ModelExplorer.Status.INCONSISTENT)
            return new Assessment(Status.INCONSISTENT, Optional.empty(), Set.of(), "Inconsistent premises");
        Set<String> compatible = new LinkedHashSet<>();
        for (var world : model.possibleWorlds())
            for (Cell cell : definition.cells())
                if (world.satisfies(cell.condition())) compatible.add(cell.name());
        if (compatible.size() == 1) {
            String name = compatible.iterator().next();
            Cell chosen = definition.cells().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
            return new Assessment(Status.DECIDED, Optional.of(chosen), compatible,
                    "One compatible answer cell, pending explicit justified response");
        }
        return new Assessment(Status.OPEN, Optional.empty(), compatible,
                "Several answer cells remain possible; the question is not repaired");
    }
}
