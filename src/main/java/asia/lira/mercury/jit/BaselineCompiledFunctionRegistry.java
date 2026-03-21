package asia.lira.mercury.jit;

import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.specialized.core.SpecializedCommandRegistry;
import net.minecraft.command.CommandExecutionContext;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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

    public void clear() {
        artifacts.clear();
        UnknownCommandBindingRegistry.getInstance().clear();
        SpecializedCommandRegistry.getInstance().clear();
    }

    public void rebuild(Collection<FunctionIrRegistry.ParsedFunctionIr> functions) {
        if (!MercuryJitRuntime.isEnabled()) {
            clear();
            return;
        }

        UnknownCommandBindingRegistry.getInstance().rebuild(functions);
        SpecializedCommandRegistry.getInstance().rebuild(functions);

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
        Map<Identifier, LoweredUnit> loweredUnits = new LinkedHashMap<>();
        for (Identifier id : compiledPrograms.keySet()) {
            loweredUnits.put(id, BaselineLowerer.lower(
                    id,
                    jumpGraph.unitFor(id),
                    collectRequiredSlots(jumpGraph.unitFor(id))
            ));
        }

        LoweredUnitInliner.InlineResult inlineResult = LoweredUnitInliner.inlineAll(loweredUnits);
        Map<Identifier, LoweredUnit> finalLoweredUnits = new LinkedHashMap<>(inlineResult.units());

        Map<Identifier, String> internalNames = new LinkedHashMap<>();
        Map<Identifier, int[]> requiredSlotsById = new LinkedHashMap<>();
        for (Map.Entry<Identifier, LoweredUnit> entry : finalLoweredUnits.entrySet()) {
            internalNames.put(entry.getKey(), BaselineBytecodeCompiler.internalNameFor(entry.getKey()));
            requiredSlotsById.put(entry.getKey(), collectRequiredSlots(entry.getValue()));
        }
        Map<Identifier, Integer> callSiteCounts = collectCallSiteCounts(finalLoweredUnits.values());
        Set<Identifier> classesWithSharedRequiredSlots = collectClassesWithSharedRequiredSlots(finalLoweredUnits.keySet(), requiredSlotsById, callSiteCounts);
        Map<Identifier, SlotEffectSummary> effectSummaries = collectEffectSummaries(finalLoweredUnits);
        BaselinePassPipeline passPipeline = new BaselinePassPipeline(List.of(
                new UnresolvedCallIsolationPass(),
                new SlotPromotionPass()
        ));

        Map<Identifier, BaselineBytecodeCompiler.CompiledClassData> compiledClasses = new LinkedHashMap<>();
        Map<Identifier, LoweredUnit> optimizedUnits = new LinkedHashMap<>();
        for (Map.Entry<Identifier, LoweredUnit> entry : finalLoweredUnits.entrySet()) {
            LoweredUnit optimizedUnit = passPipeline.apply(entry.getValue(), new BaselinePassContext(
                    internalNames,
                    callSiteCounts,
                    requiredSlotsById,
                    effectSummaries,
                    collectUnitSlots(requiredSlotsById.get(entry.getKey()))
            ));
            optimizedUnits.put(entry.getKey(), optimizedUnit);
            compiledClasses.put(entry.getKey(), BaselineBytecodeCompiler.compile(
                    optimizedUnit,
                    internalNames,
                    callSiteCounts,
                    requiredSlotsById,
                    classesWithSharedRequiredSlots
            ));
        }

        GeneratedClassLoader classLoader = new GeneratedClassLoader(BaselineCompiledFunctionRegistry.class.getClassLoader());
        Map<Identifier, Class<?>> definedClasses = new LinkedHashMap<>();
        for (Map.Entry<Identifier, BaselineBytecodeCompiler.CompiledClassData> entry : compiledClasses.entrySet()) {
            BaselineBytecodeCompiler.CompiledClassData classData = entry.getValue();
            definedClasses.put(entry.getKey(), classLoader.define(classData.internalName().replace('/', '.'), classData.classBytes()));
        }

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Map.Entry<Identifier, BaselineBytecodeCompiler.CompiledClassData> entry : compiledClasses.entrySet()) {
            Identifier id = entry.getKey();
            Class<?> definedClass = definedClasses.get(id);
            try {
                MethodHandle invokeHandle = MethodHandles.privateLookupIn(definedClass, lookup).findStatic(
                        definedClass,
                        "invoke",
                        MethodType.methodType(
                                BaselineExecutionEngine.ExecutionOutcome.class,
                                ExecutionFrame.class,
                                Object.class,
                                CommandExecutionContext.class,
                                net.minecraft.command.Frame.class,
                                int.class
                        )
                );
                BaselineBytecodeCompiler.CompiledClassData classData = entry.getValue();
                artifacts.put(id, new CompiledArtifact(
                        compiledPrograms.get(id),
                        optimizedUnits.get(id),
                        invokeHandle,
                        classData.classBytes(),
                        classData.internalName(),
                        classData.requiredSlots()
                ));
            } catch (ReflectiveOperationException exception) {
                throw new RuntimeException("Failed to bind compiled class for " + id, exception);
            }
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
        return true;
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

    private static int[] collectRequiredSlots(LoweredUnit unit) {
        Set<Integer> slotIds = new LinkedHashSet<>();
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                switch (instruction) {
                    case LoweredUnit.SetConstInstruction setConst -> slotIds.add(setConst.slotId());
                    case LoweredUnit.AddConstInstruction addConst -> slotIds.add(addConst.slotId());
                    case LoweredUnit.GetInstruction get -> slotIds.add(get.slotId());
                    case LoweredUnit.ResetInstruction reset -> slotIds.add(reset.slotId());
                    case LoweredUnit.OperationInstruction operation -> {
                        slotIds.add(operation.primarySlot());
                        slotIds.add(operation.secondarySlot());
                    }
                    default -> {
                    }
                }
            }
        }
        return slotIds.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Map<Identifier, Integer> collectCallSiteCounts(
            Collection<LoweredUnit> units
    ) {
        Map<Identifier, Integer> callSiteCounts = new LinkedHashMap<>();
        for (LoweredUnit unit : units) {
            for (LoweredUnit.LoweredBlock block : unit.blocks()) {
                for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                    if (instruction instanceof LoweredUnit.CallInstruction callInstruction) {
                        callSiteCounts.merge(callInstruction.targetFunction(), 1, Integer::sum);
                    }
                }
            }
        }
        return callSiteCounts;
    }

    private static Set<Identifier> collectClassesWithSharedRequiredSlots(
            Set<Identifier> compiledIds,
            Map<Identifier, int[]> requiredSlotsById,
            Map<Identifier, Integer> callSiteCounts
    ) {
        Set<Identifier> shared = new LinkedHashSet<>();
        for (Identifier id : compiledIds) {
            int slotCount = requiredSlotsById.getOrDefault(id, new int[0]).length;
            int callSites = callSiteCounts.getOrDefault(id, 0);
            if (!BaselineBytecodeCompiler.shouldInlineRequiredSlots(slotCount, callSites)) {
                shared.add(id);
            }
        }
        return shared;
    }

    private static Map<Identifier, SlotEffectSummary> collectEffectSummaries(Map<Identifier, LoweredUnit> units) {
        Map<Identifier, Set<Integer>> readSets = new LinkedHashMap<>();
        Map<Identifier, Set<Integer>> writeSets = new LinkedHashMap<>();
        Map<Identifier, Set<Integer>> resetSets = new LinkedHashMap<>();

        for (Map.Entry<Identifier, LoweredUnit> entry : units.entrySet()) {
            Set<Integer> reads = new LinkedHashSet<>();
            Set<Integer> writes = new LinkedHashSet<>();
            Set<Integer> resets = new LinkedHashSet<>();

            for (LoweredUnit.LoweredBlock block : entry.getValue().blocks()) {
                for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                    switch (instruction) {
                        case LoweredUnit.SetConstInstruction setConst -> writes.add(setConst.slotId());
                        case LoweredUnit.AddConstInstruction addConst -> {
                            reads.add(addConst.slotId());
                            writes.add(addConst.slotId());
                        }
                        case LoweredUnit.GetInstruction get -> reads.add(get.slotId());
                        case LoweredUnit.ResetInstruction reset -> {
                            resets.add(reset.slotId());
                            writes.add(reset.slotId());
                        }
                        case LoweredUnit.OperationInstruction operation -> {
                            switch (operation.operation()) {
                            case "=" -> {
                                    reads.add(operation.secondarySlot());
                                    writes.add(operation.primarySlot());
                            }
                            case "><" -> {
                                    reads.add(operation.primarySlot());
                                    reads.add(operation.secondarySlot());
                                    writes.add(operation.primarySlot());
                                    writes.add(operation.secondarySlot());
                            }
                            default -> {
                                    reads.add(operation.primarySlot());
                                    reads.add(operation.secondarySlot());
                                    writes.add(operation.primarySlot());
                                }
                            }
                        }
                        default -> {
                        }
                    }
                }
            }

            readSets.put(entry.getKey(), reads);
            writeSets.put(entry.getKey(), writes);
            resetSets.put(entry.getKey(), resets);
        }

        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Identifier, LoweredUnit> entry : units.entrySet()) {
                Set<Integer> reads = readSets.get(entry.getKey());
                Set<Integer> writes = writeSets.get(entry.getKey());
                for (LoweredUnit.LoweredBlock block : entry.getValue().blocks()) {
                    for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                        if (!(instruction instanceof LoweredUnit.CallInstruction callInstruction)) {
                            continue;
                        }
                        SlotEffectSummary calleeSummary = summaryOf(callInstruction.targetFunction(), readSets, writeSets, resetSets);
                        if (calleeSummary == null) {
                            continue;
                        }
                        changed |= reads.addAll(calleeSummary.readSlots());
                        changed |= writes.addAll(calleeSummary.writtenSlots());
                        changed |= writes.addAll(calleeSummary.resetSlots());
                    }
                }
            }
        } while (changed);

        Map<Identifier, SlotEffectSummary> summaries = new LinkedHashMap<>();
        for (Identifier id : units.keySet()) {
            summaries.put(id, new SlotEffectSummary(
                    readSets.getOrDefault(id, Set.of()),
                    writeSets.getOrDefault(id, Set.of()),
                    resetSets.getOrDefault(id, Set.of())
            ));
        }
        return summaries;
    }

    private static Set<Integer> collectUnitSlots(int[] requiredSlots) {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int requiredSlot : requiredSlots) {
            slots.add(requiredSlot);
        }
        return slots;
    }

    private static SlotEffectSummary summaryOf(
            Identifier id,
            Map<Identifier, Set<Integer>> readSets,
            Map<Identifier, Set<Integer>> writeSets,
            Map<Identifier, Set<Integer>> resetSets
    ) {
        if (!readSets.containsKey(id) && !writeSets.containsKey(id) && !resetSets.containsKey(id)) {
            return null;
        }
        return new SlotEffectSummary(
                readSets.getOrDefault(id, Set.of()),
                writeSets.getOrDefault(id, Set.of()),
                resetSets.getOrDefault(id, Set.of())
        );
    }

    public record CompiledArtifact(
            BaselineProgram program,
            LoweredUnit optimizedUnit,
            MethodHandle invokeHandle,
            byte[] classBytes,
            String internalClassName,
            int[] requiredSlots
    ) {
        public BaselineExecutionEngine.ExecutionOutcome invoke(ExecutionFrame frame, Object source, CommandExecutionContext<?> context, net.minecraft.command.Frame commandFrame, int initialState) throws Throwable {
            return (BaselineExecutionEngine.ExecutionOutcome) invokeHandle.invoke(frame, source, context, commandFrame, initialState);
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

    private static final class GeneratedClassLoader extends ClassLoader {
        private GeneratedClassLoader(ClassLoader parent) {
            super(parent);
        }

        private Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
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
