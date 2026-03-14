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

    private final Map<Identifier, BaselineProgram> programs = new LinkedHashMap<>();

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

        Map<Identifier, BaselineProgram> compiled = new LinkedHashMap<>();
        for (Map.Entry<Identifier, BaselineProgram.Builder> entry : candidates.entrySet()) {
            if (isCompilable(entry.getKey(), candidates, compiled, new LinkedHashMap<>())) {
                compiled.put(entry.getKey(), entry.getValue().build());
            }
        }

        programs.clear();
        programs.putAll(compiled);
    }

    public int count() {
        return programs.size();
    }

    public List<Identifier> ids() {
        return List.copyOf(programs.keySet());
    }

    public @Nullable BaselineProgram get(Identifier id) {
        return programs.get(id);
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
}
