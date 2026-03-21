package asia.lira.mercury.jit.specialized.core;

import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.specialized.api.SpecializationAnalyzer;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import asia.lira.mercury.jit.specialized.impl.data.DataModifyStorageAnalyzer;
import asia.lira.mercury.jit.specialized.impl.execute.ExecuteAnalyzer;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpecializedCommandRegistry {
    private static final SpecializedCommandRegistry INSTANCE = new SpecializedCommandRegistry();

    private final List<SpecializationAnalyzer> analyzers = List.of(
            new ExecuteAnalyzer(),
            new DataModifyStorageAnalyzer()
    );
    private final Map<BindingKey, Integer> idsByKey = new LinkedHashMap<>();
    private final Map<Integer, SpecializedPlan> plansById = new LinkedHashMap<>();

    private SpecializedCommandRegistry() {
    }

    public static SpecializedCommandRegistry getInstance() {
        return INSTANCE;
    }

    public void clear() {
        idsByKey.clear();
        plansById.clear();
    }

    public void rebuild(Collection<FunctionIrRegistry.ParsedFunctionIr> functions) {
        clear();
        int nextId = 0;
        for (FunctionIrRegistry.ParsedFunctionIr functionIr : functions) {
            for (int nodeIndex = 0; nodeIndex < functionIr.nodes().size(); nodeIndex++) {
                if (!(functionIr.nodes().get(nodeIndex) instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                    continue;
                }

                SpecializedPlan plan = analyze(commandNode.sourceText());
                if (plan == null) {
                    continue;
                }

                idsByKey.put(new BindingKey(functionIr.id(), nodeIndex), nextId);
                plansById.put(nextId, plan);
                nextId++;
            }
        }
    }

    public @Nullable Integer specializedId(Identifier functionId, int nodeIndex) {
        return idsByKey.get(new BindingKey(functionId, nodeIndex));
    }

    public @Nullable SpecializedPlan plan(int id) {
        return plansById.get(id);
    }

    public static SpecializedPlan requirePlan(int id) {
        SpecializedPlan plan = INSTANCE.plan(id);
        if (plan == null) {
            throw new IllegalStateException("Missing specialized plan " + id);
        }
        return plan;
    }

    private @Nullable SpecializedPlan analyze(String sourceText) {
        for (SpecializationAnalyzer analyzer : analyzers) {
            SpecializedPlan plan = analyzer.analyze(sourceText);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private record BindingKey(Identifier functionId, int nodeIndex) {
    }
}
