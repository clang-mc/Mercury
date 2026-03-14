package asia.lira.mercury.jit;

import asia.lira.mercury.ir.FunctionIrRegistry;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        for (Map.Entry<Identifier, BaselineProgram> entry : compiledPrograms.entrySet()) {
            JumpGraph.CompilationUnit unit = jumpGraph.unitFor(entry.getKey());
            artifacts.put(entry.getKey(), BaselineBytecodeCompiler.compile(entry.getValue(), unit));
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

    public record CompiledArtifact(
            BaselineProgram program,
            CompiledFunction compiledFunction,
            byte[] classBytes,
            String internalClassName
    ) {
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
}
