package asia.lira.mercury.jit;

import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.ir.FunctionParseCaptureRegistry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GlobalCompilationCoordinator {
    private static final GlobalCompilationCoordinator INSTANCE = new GlobalCompilationCoordinator();

    private volatile Map<Identifier, List<String>> rawSources = Map.of();

    private GlobalCompilationCoordinator() {
    }

    public static GlobalCompilationCoordinator getInstance() {
        return INSTANCE;
    }

    public void beginReload(Map<Identifier, List<String>> rawSources) {
        this.rawSources = Map.copyOf(rawSources);
        FunctionParseCaptureRegistry.getInstance().beginReload(rawSources.keySet());
    }

    public Map<Identifier, CommandFunction<ServerCommandSource>> finishReload(Map<Identifier, CommandFunction<ServerCommandSource>> loadedFunctions) {
        FunctionIrRegistry.getInstance().rebuildAll(
                rawSources,
                FunctionParseCaptureRegistry.getInstance().snapshot(),
                loadedFunctions
        );

        boolean jitEnabled = MercuryJitRuntime.isEnabled();
        if (!jitEnabled) {
            BaselineCompiledFunctionRegistry.getInstance().clear();
        }

        Map<Identifier, CommandFunction<ServerCommandSource>> wrapped = new LinkedHashMap<>();
        for (Map.Entry<Identifier, CommandFunction<ServerCommandSource>> entry : loadedFunctions.entrySet()) {
            CommandFunction<ServerCommandSource> function = entry.getValue();
            if (jitEnabled
                    && function instanceof Procedure<?> procedure
                    && !(function instanceof CompiledFunctionWrapper<?>)
                    && BaselineCompiledFunctionRegistry.getInstance().get(entry.getKey()) != null) {
                @SuppressWarnings("unchecked")
                Procedure<ServerCommandSource> typedProcedure = (Procedure<ServerCommandSource>) procedure;
                wrapped.put(entry.getKey(), new CompiledFunctionWrapper<>(entry.getKey(), typedProcedure));
                continue;
            }
            wrapped.put(entry.getKey(), function);
        }

        FunctionParseCaptureRegistry.getInstance().clear();
        this.rawSources = Map.of();
        return Map.copyOf(wrapped);
    }
}
