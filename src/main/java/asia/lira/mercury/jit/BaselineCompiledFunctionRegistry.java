package asia.lira.mercury.jit;

import asia.lira.mercury.ir.FunctionIrRegistry;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BaselineCompiledFunctionRegistry {
    private static final BaselineCompiledFunctionRegistry INSTANCE = new BaselineCompiledFunctionRegistry();

    private final Map<Identifier, CompiledArtifact> artifacts = new LinkedHashMap<>();

    private BaselineCompiledFunctionRegistry() {
    }

    public static BaselineCompiledFunctionRegistry getInstance() {
        return INSTANCE;
    }

    public void rebuild(Collection<FunctionIrRegistry.ParsedFunctionIr> functions) {
        Map<Identifier, BaselineProgram.Builder> candidates = new LinkedHashMap<>();
        for (FunctionIrRegistry.ParsedFunctionIr functionIr : functions) {
            BaselineProgram.Builder builder = BaselineCompiler.analyze(functionIr);
            if (builder != null) {
                candidates.put(functionIr.id(), builder);
            }
        }

        Map<Identifier, BaselineProgram> compiledPrograms = new LinkedHashMap<>();
        for (Map.Entry<Identifier, BaselineProgram.Builder> entry : candidates.entrySet()) {
            if (isCompilable(entry.getKey(), candidates, compiledPrograms, new LinkedHashMap<>())) {
                compiledPrograms.put(entry.getKey(), entry.getValue().build());
            }
        }

        artifacts.clear();
        JumpGraph jumpGraph = JumpGraph.build(compiledPrograms);
        CallGraph callGraph = CallGraph.build(compiledPrograms);
        Map<Identifier, Boolean> visiting = new LinkedHashMap<>();
        for (Identifier id : compiledPrograms.keySet()) {
            compileArtifact(id, compiledPrograms, jumpGraph, callGraph, visiting);
        }
    }

    public int count() {
        return artifacts.size();
    }

    public List<Identifier> ids() {
        return List.copyOf(artifacts.keySet());
    }

    public @Nullable BaselineProgram get(Identifier id) {
        CompiledArtifact artifact = artifacts.get(id);
        return artifact == null ? null : artifact.program();
    }

    public @Nullable CompiledArtifact getArtifact(Identifier id) {
        return artifacts.get(id);
    }

    private boolean isCompilable(
            Identifier id,
            Map<Identifier, BaselineProgram.Builder> candidates,
            Map<Identifier, BaselineProgram> compiled,
            Map<Identifier, Boolean> visiting
    ) {
        if (compiled.containsKey(id)) {
            return true;
        }

        Boolean state = visiting.get(id);
        if (state != null) {
            return state;
        }

        BaselineProgram.Builder builder = candidates.get(id);
        if (builder == null) {
            visiting.put(id, false);
            return false;
        }

        visiting.put(id, true);
        for (Identifier dependency : builder.dependencies()) {
            if (!isCompilable(dependency, candidates, compiled, visiting)) {
                visiting.put(id, false);
                return false;
            }
        }

        return true;
    }

    private @Nullable CompiledArtifact compileArtifact(
            Identifier id,
            Map<Identifier, BaselineProgram> compiledPrograms,
            JumpGraph jumpGraph,
            CallGraph callGraph,
            Map<Identifier, Boolean> visiting
    ) {
        CompiledArtifact existing = artifacts.get(id);
        if (existing != null) {
            return existing;
        }

        if (Boolean.TRUE.equals(visiting.get(id))) {
            return null;
        }

        BaselineProgram program = compiledPrograms.get(id);
        if (program == null) {
            return null;
        }

        visiting.put(id, true);
        JumpGraph.CompilationUnit unit = jumpGraph.unitFor(id);
        Set<Identifier> localPrograms = new LinkedHashSet<>();
        for (BaselineProgram localProgram : unit.programs()) {
            localPrograms.add(localProgram.id());
        }

        for (Identifier dependency : collectExternalDependencies(unit, localPrograms)) {
            if (callGraph.areInSameComponent(id, dependency)) {
                continue;
            }
            compileArtifact(dependency, compiledPrograms, jumpGraph, callGraph, visiting);
        }

        List<BaselineBytecodeCompiler.DirectCallee> directCallees = collectDirectCallees(unit, localPrograms, callGraph, id);
        CompiledArtifact artifact = BaselineBytecodeCompiler.compile(program, unit, collectRequiredSlots(unit), directCallees);
        artifacts.put(id, artifact);
        visiting.remove(id);
        return artifact;
    }

    private Set<Identifier> collectExternalDependencies(JumpGraph.CompilationUnit unit, Set<Identifier> localPrograms) {
        Set<Identifier> dependencies = new LinkedHashSet<>();
        for (BaselineProgram program : unit.programs()) {
            for (Identifier dependency : program.dependencies()) {
                if (!localPrograms.contains(dependency)) {
                    dependencies.add(dependency);
                }
            }
        }
        return dependencies;
    }

    private List<BaselineBytecodeCompiler.DirectCallee> collectDirectCallees(
            JumpGraph.CompilationUnit unit,
            Set<Identifier> localPrograms,
            CallGraph callGraph,
            Identifier entryId
    ) {
        Set<Identifier> seen = new LinkedHashSet<>();
        List<BaselineBytecodeCompiler.DirectCallee> directCallees = new java.util.ArrayList<>();
        int callerComponent = callGraph.componentOf(entryId);

        for (BaselineProgram program : unit.programs()) {
            for (Identifier dependency : program.dependencies()) {
                if (localPrograms.contains(dependency) || !seen.add(dependency)) {
                    continue;
                }
                if (callGraph.componentOf(dependency) == callerComponent) {
                    continue;
                }

                CompiledArtifact artifact = artifacts.get(dependency);
                if (artifact != null) {
                    directCallees.add(new BaselineBytecodeCompiler.DirectCallee(dependency, artifact.compiledFunction(), artifact.requiredSlots()));
                }
            }
        }

        return List.copyOf(directCallees);
    }

    public record CompiledArtifact(
            BaselineProgram program,
            CompiledFunction compiledFunction,
            byte[] classBytes,
            String internalClassName,
            int[] requiredSlots
    ) {
    }

    private static int[] collectRequiredSlots(JumpGraph.CompilationUnit unit) {
        Set<Integer> slotIds = new LinkedHashSet<>();
        for (BaselineProgram program : unit.programs()) {
            for (BaselineInstruction instruction : program.instructions()) {
                if (instruction.primarySlot() >= 0) {
                    slotIds.add(instruction.primarySlot());
                }
                if (instruction.secondarySlot() >= 0) {
                    slotIds.add(instruction.secondarySlot());
                }
            }
        }
        return slotIds.stream().mapToInt(Integer::intValue).toArray();
    }

    private record CallGraph(Map<Identifier, Integer> components) {
        static CallGraph build(Map<Identifier, BaselineProgram> programs) {
            Map<Identifier, List<Identifier>> edges = new LinkedHashMap<>();
            for (Map.Entry<Identifier, BaselineProgram> entry : programs.entrySet()) {
                edges.put(entry.getKey(), entry.getValue().dependencies().stream()
                        .filter(programs::containsKey)
                        .toList());
            }

            Tarjan tarjan = new Tarjan(edges);
            Map<Identifier, Integer> components = new LinkedHashMap<>();
            List<List<Identifier>> groups = tarjan.components();
            for (int index = 0; index < groups.size(); index++) {
                for (Identifier id : groups.get(index)) {
                    components.put(id, index);
                }
            }
            return new CallGraph(components);
        }

        boolean areInSameComponent(Identifier left, Identifier right) {
            return componentOf(left) == componentOf(right);
        }

        int componentOf(Identifier id) {
            return components.getOrDefault(id, -1);
        }
    }

    record JumpGraph(Map<Identifier, CompilationUnit> units) {
        static JumpGraph build(Map<Identifier, BaselineProgram> programs) {
            Map<Identifier, List<Identifier>> edges = new LinkedHashMap<>();
            for (Map.Entry<Identifier, BaselineProgram> entry : programs.entrySet()) {
                List<Identifier> targets = entry.getValue().instructions().stream()
                        .filter(instruction -> instruction.opCode() == BaselineInstruction.OpCode.JUMP && programs.containsKey(instruction.targetFunction()))
                        .map(BaselineInstruction::targetFunction)
                        .toList();
                edges.put(entry.getKey(), targets);
            }

            Tarjan tarjan = new Tarjan(edges);
            Map<Identifier, CompilationUnit> units = new LinkedHashMap<>();
            for (List<Identifier> component : tarjan.components()) {
                List<BaselineProgram> componentPrograms = component.stream().map(programs::get).toList();
                for (int i = 0; i < component.size(); i++) {
                    units.put(component.get(i), new CompilationUnit(componentPrograms, i));
                }
            }
            return new JumpGraph(units);
        }

        CompilationUnit unitFor(Identifier id) {
            return units.get(id);
        }

        record CompilationUnit(List<BaselineProgram> programs, int entryIndex) {
            int indexOf(Identifier id) {
                for (int i = 0; i < programs.size(); i++) {
                    if (programs.get(i).id().equals(id)) {
                        return i;
                    }
                }
                return -1;
            }
        }
    }

    private static final class Tarjan {
        private final Map<Identifier, List<Identifier>> edges;
        private final Map<Identifier, Integer> indices = new LinkedHashMap<>();
        private final Map<Identifier, Integer> lowLinks = new LinkedHashMap<>();
        private final java.util.ArrayDeque<Identifier> stack = new java.util.ArrayDeque<>();
        private final java.util.Set<Identifier> onStack = new java.util.HashSet<>();
        private final java.util.List<java.util.List<Identifier>> components = new java.util.ArrayList<>();
        private int nextIndex;

        private Tarjan(Map<Identifier, List<Identifier>> edges) {
            this.edges = edges;
            for (Identifier id : edges.keySet()) {
                if (!indices.containsKey(id)) {
                    strongConnect(id);
                }
            }
        }

        private void strongConnect(Identifier id) {
            indices.put(id, nextIndex);
            lowLinks.put(id, nextIndex);
            nextIndex++;
            stack.push(id);
            onStack.add(id);

            for (Identifier target : edges.getOrDefault(id, List.of())) {
                if (!indices.containsKey(target)) {
                    strongConnect(target);
                    lowLinks.put(id, Math.min(lowLinks.get(id), lowLinks.get(target)));
                } else if (onStack.contains(target)) {
                    lowLinks.put(id, Math.min(lowLinks.get(id), indices.get(target)));
                }
            }

            if (lowLinks.get(id).equals(indices.get(id))) {
                java.util.List<Identifier> component = new java.util.ArrayList<>();
                Identifier value;
                do {
                    value = stack.pop();
                    onStack.remove(value);
                    component.add(value);
                } while (!value.equals(id));
                components.add(List.copyOf(component));
            }
        }

        private List<List<Identifier>> components() {
            return List.copyOf(components);
        }
    }
}
