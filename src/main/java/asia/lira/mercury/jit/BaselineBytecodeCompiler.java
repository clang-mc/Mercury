package asia.lira.mercury.jit;

import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import asia.lira.mercury.jit.specialized.core.SpecializedCommandRegistry;
import asia.lira.mercury.jit.specialized.impl.data.DataModifyStorageExecutor;
import asia.lira.mercury.jit.specialized.impl.data.DataModifyStoragePlan;
import asia.lira.mercury.jit.specialized.impl.execute.ExecuteExecutor;
import asia.lira.mercury.jit.specialized.impl.execute.ExecutePlan;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class BaselineBytecodeCompiler {
    private static final String EXECUTION_FRAME_DESC = Type.getDescriptor(ExecutionFrame.class);
    private static final String OUTCOME_DESC = Type.getDescriptor(BaselineExecutionEngine.ExecutionOutcome.class);
    public static final String REQUIRED_SLOTS_FIELD = "REQUIRED_SLOTS";

    private BaselineBytecodeCompiler() {
    }

    public static CompiledClassData compile(
            LoweredUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        String internalName = internalNameFor(unit.entryId());
        byte[] classBytes = generateClass(unit, internalName, internalNames, callSiteCounts, requiredSlotsById, classesWithSharedRequiredSlots);
        return new CompiledClassData(unit.entryId(), internalName, classBytes, unit.requiredSlots());
    }

    public static String internalNameFor(net.minecraft.util.Identifier id) {
        String namespace = sanitize(id.getNamespace());
        String path = sanitize(id.getPath());
        return "asia/lira/mercury/jit/Generated_" + namespace + "_" + path + "_" + Integer.toHexString(id.hashCode());
    }

    private static byte[] generateClass(
            LoweredUnit unit,
            String internalName,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Set<net.minecraft.util.Identifier> classesWithSharedRequiredSlots
    ) {
        Map<Integer, SpecializedFieldSpec> specializedFields = collectSpecializedFields(unit);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);

        boolean emitRequiredSlotsField = classesWithSharedRequiredSlots.contains(unit.entryId());
        if (emitRequiredSlotsField) {
            writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, REQUIRED_SLOTS_FIELD, "[I", null, null).visitEnd();
        }
        for (SpecializedFieldSpec fieldSpec : specializedFields.values()) {
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, fieldSpec.fieldName(), fieldSpec.planDescriptor(), null, null).visitEnd();
        }

        emitConstructor(writer);
        if (emitRequiredSlotsField || !specializedFields.isEmpty()) {
            emitClassInitializer(writer, internalName, unit.requiredSlots(), emitRequiredSlotsField, specializedFields);
        }
        emitInvoke(writer, unit, internalNames, callSiteCounts, requiredSlotsById, specializedFields);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitClassInitializer(
            ClassWriter writer,
            String internalName,
            int[] requiredSlots,
            boolean emitRequiredSlotsField,
            Map<Integer, SpecializedFieldSpec> specializedFields
    ) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        visitor.visitCode();
        if (emitRequiredSlotsField) {
            BaselineBytecodeOps.buildIntArray(visitor, requiredSlots);
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, REQUIRED_SLOTS_FIELD, "[I");
        }
        for (SpecializedFieldSpec fieldSpec : specializedFields.values()) {
            BaselineBytecodeOps.pushInt(visitor, fieldSpec.specializedId());
            visitor.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    Type.getInternalName(SpecializedCommandRegistry.class),
                    "requirePlan",
                    "(I)" + Type.getDescriptor(SpecializedPlan.class),
                    false
            );
            visitor.visitTypeInsn(Opcodes.CHECKCAST, fieldSpec.planInternalName());
            visitor.visitFieldInsn(Opcodes.PUTSTATIC, internalName, fieldSpec.fieldName(), fieldSpec.planDescriptor());
        }
        visitor.visitInsn(Opcodes.RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitInvoke(
            ClassWriter writer,
            LoweredUnit unit,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields
    ) {
        String descriptor = "("
                + EXECUTION_FRAME_DESC
                + "Ljava/lang/Object;"
                + Type.getDescriptor(net.minecraft.command.CommandExecutionContext.class)
                + Type.getDescriptor(net.minecraft.command.Frame.class)
                + "I"
                + ")"
                + OUTCOME_DESC;
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", descriptor, null, new String[]{"java/lang/Throwable"});
        visitor.visitCode();

        for (Map.Entry<Integer, Integer> promoted : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildInitializePromotedSlot(visitor, promoted.getKey(), promoted.getValue());
        }

        Label loopStart = new Label();
        Label defaultLabel = new Label();
        Label[] stateLabels = new Label[unit.blocks().size()];
        for (int i = 0; i < stateLabels.length; i++) {
            stateLabels[i] = new Label();
        }

        visitor.visitLabel(loopStart);
        visitor.visitVarInsn(Opcodes.ILOAD, 4);
            visitor.visitTableSwitchInsn(0, stateLabels.length - 1, defaultLabel, stateLabels);

        for (int i = 0; i < unit.blocks().size(); i++) {
            visitor.visitLabel(stateLabels[i]);
            emitBlock(visitor, unit, unit.blocks().get(i), loopStart, internalNames, callSiteCounts, requiredSlotsById, specializedFields, internalNameFor(unit.entryId()));
        }

        visitor.visitLabel(defaultLabel);
        spillAllPromoted(visitor, unit);
        BaselineBytecodeOps.buildCompleted(visitor);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void emitBlock(
            MethodVisitor visitor,
            LoweredUnit unit,
            LoweredUnit.LoweredBlock block,
            Label loopStart,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            String ownerInternalName
    ) {
        for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
            switch (instruction) {
                case LoweredUnit.SetConstInstruction setConst ->
                        emitSetConst(visitor, unit, setConst.slotId(), setConst.value());
                case LoweredUnit.AddConstInstruction addConst ->
                        emitAddConst(visitor, unit, addConst.slotId(), addConst.delta());
                case LoweredUnit.GetInstruction get ->
                        emitGet(visitor, unit, get.slotId());
                case LoweredUnit.ResetInstruction reset ->
                        BaselineBytecodeOps.buildReset(visitor, reset.slotId());
                case LoweredUnit.OperationInstruction operation ->
                        emitOperation(visitor, unit, operation.primarySlot(), operation.secondarySlot(), operation.operation());
                case LoweredUnit.CallInstruction call ->
                        emitDirectInvoke(visitor, unit, call.targetFunction(), call.bindingId(), call.spillBeforeSlots(), call.reloadAfterSlots(), internalNames, callSiteCounts, requiredSlotsById, false);
                case LoweredUnit.ReflectiveBridgeInstruction reflectiveBridge ->
                        emitReflectiveBridge(visitor, unit, reflectiveBridge.bindingId(), reflectiveBridge.spillBeforeSlots(), reflectiveBridge.reloadAfterSlots());
                case LoweredUnit.ActionBridgeInstruction actionBridge ->
                        emitActionBridge(visitor, unit, actionBridge.bindingId(), actionBridge.spillBeforeSlots(), actionBridge.reloadAfterSlots());
                case LoweredUnit.SpecializedInstruction specializedInstruction ->
                        emitSpecialized(visitor, unit, specializedInstruction.specializedId(), specializedInstruction.spillBeforeSlots(), specializedInstruction.reloadAfterSlots(), specializedFields, ownerInternalName);
            }
        }

        switch (block.terminator()) {
            case LoweredUnit.CompleteTerminator ignored -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildCompleted(visitor);
            }
            case LoweredUnit.ReturnValueTerminator returnValue -> {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildReturnValue(visitor, returnValue.returnValue());
            }
            case LoweredUnit.JumpLocalTerminator jumpLocal -> {
                BaselineBytecodeOps.pushInt(visitor, jumpLocal.targetBlockIndex());
                visitor.visitVarInsn(Opcodes.ISTORE, 4);
                visitor.visitJumpInsn(Opcodes.GOTO, loopStart);
            }
            case LoweredUnit.JumpExternalTerminator jumpExternal -> {
                spillAllPromoted(visitor, unit);
                emitDirectInvoke(visitor, unit, jumpExternal.targetFunction(), jumpExternal.bindingId(), jumpExternal.spillBeforeSlots(), new int[0], internalNames, callSiteCounts, requiredSlotsById, true);
            }
            case LoweredUnit.SuspendActionTerminator suspendAction -> {
                spillPromotedSlots(visitor, unit, suspendAction.spillBeforeSlots());
                BaselineBytecodeOps.buildSuspend(visitor, suspendAction.bindingId(), suspendAction.continuationBlockIndex());
            }
        }
    }

    private static void emitSetConst(MethodVisitor visitor, LoweredUnit unit, int slotId, int value) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildStorePromotedLocal(visitor, unit.localIndexFor(slotId), value);
        } else {
            BaselineBytecodeOps.buildSetConst(visitor, slotId, value);
        }
    }

    private static void emitAddConst(MethodVisitor visitor, LoweredUnit unit, int slotId, int delta) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildAddPromotedLocal(visitor, unit.localIndexFor(slotId), delta);
        } else {
            BaselineBytecodeOps.buildAddConst(visitor, slotId, delta);
        }
    }

    private static void emitGet(MethodVisitor visitor, LoweredUnit unit, int slotId) {
        if (unit.isPromoted(slotId)) {
            BaselineBytecodeOps.buildGetPromotedLocal(visitor, unit.localIndexFor(slotId));
        } else {
            BaselineBytecodeOps.buildGet(visitor, slotId);
        }
    }

    private static void emitOperation(MethodVisitor visitor, LoweredUnit unit, int primarySlot, int secondarySlot, String operation) {
        if (unit.isPromoted(primarySlot) && unit.isPromoted(secondarySlot)) {
            BaselineBytecodeOps.buildPromotedOperation(visitor, unit.localIndexFor(primarySlot), unit.localIndexFor(secondarySlot), operation);
        } else if (unit.isPromoted(primarySlot)) {
            BaselineBytecodeOps.buildMixedOperationPrimaryPromoted(visitor, unit.localIndexFor(primarySlot), secondarySlot, operation);
        } else if (unit.isPromoted(secondarySlot)) {
            BaselineBytecodeOps.buildMixedOperationSecondaryPromoted(visitor, primarySlot, unit.localIndexFor(secondarySlot), operation);
        } else {
            BaselineBytecodeOps.buildOperation(visitor, primarySlot, secondarySlot, operation);
        }
    }

    private static void emitDirectInvoke(
            MethodVisitor visitor,
            LoweredUnit unit,
            net.minecraft.util.Identifier targetFunction,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            Map<net.minecraft.util.Identifier, String> internalNames,
            Map<net.minecraft.util.Identifier, Integer> callSiteCounts,
            Map<net.minecraft.util.Identifier, int[]> requiredSlotsById,
            boolean returnOutcome
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);

        String targetInternalName = internalNames.get(targetFunction);
        if (targetInternalName == null) {
            if (bindingId < 0) {
                throw new IllegalStateException("Missing fallback binding for unresolved call target " + targetFunction + " in " + unit.entryId());
            }
            BaselineBytecodeOps.buildInvokeActionFallback(visitor, bindingId);
            if (returnOutcome) {
                spillAllPromoted(visitor, unit);
                BaselineBytecodeOps.buildCompleted(visitor);
            }
            reloadPromotedSlots(visitor, unit, reloadAfterSlots);
            return;
        }

        int[] requiredSlots = requiredSlotsById.getOrDefault(targetFunction, new int[0]);
        int callSites = callSiteCounts.getOrDefault(targetFunction, 0);
        if (shouldInlineRequiredSlots(requiredSlots.length, callSites)) {
            BaselineBytecodeOps.buildInlineRequiredSlots(visitor, requiredSlots);
        } else {
            BaselineBytecodeOps.buildEnsureLoadedFromStaticField(visitor, targetInternalName, REQUIRED_SLOTS_FIELD);
        }
        BaselineBytecodeOps.buildStaticInvoke(visitor, targetInternalName, returnOutcome);
        if (!returnOutcome) {
            reloadPromotedSlots(visitor, unit, reloadAfterSlots);
        }
    }

    private static void emitReflectiveBridge(
            MethodVisitor visitor,
            LoweredUnit unit,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildInvokeBoundCommand(visitor, bindingId);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitActionBridge(
            MethodVisitor visitor,
            LoweredUnit unit,
            int bindingId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        BaselineBytecodeOps.buildInvokeActionFallback(visitor, bindingId);
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static void emitSpecialized(
            MethodVisitor visitor,
            LoweredUnit unit,
            int specializedId,
            int[] spillBeforeSlots,
            int[] reloadAfterSlots,
            Map<Integer, SpecializedFieldSpec> specializedFields,
            String ownerInternalName
    ) {
        spillPromotedSlots(visitor, unit, spillBeforeSlots);
        SpecializedFieldSpec fieldSpec = specializedFields.get(specializedId);
        if (fieldSpec == null) {
            throw new IllegalStateException("Missing specialized field for plan " + specializedId + " in " + unit.entryId());
        }
        visitor.visitFieldInsn(Opcodes.GETSTATIC, ownerInternalName, fieldSpec.fieldName(), fieldSpec.planDescriptor());
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);
        visitor.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(net.minecraft.server.command.ServerCommandSource.class));
        visitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                fieldSpec.executorOwner(),
                fieldSpec.executorMethod(),
                fieldSpec.executorDescriptor(),
                false
        );
        reloadPromotedSlots(visitor, unit, reloadAfterSlots);
    }

    private static Map<Integer, SpecializedFieldSpec> collectSpecializedFields(LoweredUnit unit) {
        Map<Integer, SpecializedFieldSpec> fieldSpecs = new LinkedHashMap<>();
        for (LoweredUnit.LoweredBlock block : unit.blocks()) {
            for (LoweredUnit.LoweredInstruction instruction : block.instructions()) {
                if (!(instruction instanceof LoweredUnit.SpecializedInstruction specializedInstruction)) {
                    continue;
                }
                fieldSpecs.computeIfAbsent(specializedInstruction.specializedId(), BaselineBytecodeCompiler::specializedFieldSpec);
            }
        }
        return fieldSpecs;
    }

    private static SpecializedFieldSpec specializedFieldSpec(int specializedId) {
        SpecializedPlan plan = SpecializedCommandRegistry.requirePlan(specializedId);
        if (plan instanceof ExecutePlan) {
            return new SpecializedFieldSpec(
                    specializedId,
                    "SPECIALIZED_PLAN_" + specializedId,
                    Type.getDescriptor(ExecutePlan.class),
                    Type.getInternalName(ExecutePlan.class),
                    Type.getInternalName(ExecuteExecutor.class),
                    "executeDirect",
                    "(" + Type.getDescriptor(ExecutePlan.class) + EXECUTION_FRAME_DESC + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class) + ")V"
            );
        }
        if (plan instanceof DataModifyStoragePlan) {
            return new SpecializedFieldSpec(
                    specializedId,
                    "SPECIALIZED_PLAN_" + specializedId,
                    Type.getDescriptor(DataModifyStoragePlan.class),
                    Type.getInternalName(DataModifyStoragePlan.class),
                    Type.getInternalName(DataModifyStorageExecutor.class),
                    "executeDirect",
                    "(" + Type.getDescriptor(DataModifyStoragePlan.class) + EXECUTION_FRAME_DESC + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class) + ")V"
            );
        }
        throw new IllegalStateException("Unsupported specialized plan type " + plan.getClass().getName());
    }

    private static void spillAllPromoted(MethodVisitor visitor, LoweredUnit unit) {
        for (Map.Entry<Integer, Integer> entry : unit.promotedSlotLocals().entrySet()) {
            BaselineBytecodeOps.buildSpillPromotedSlot(visitor, entry.getKey(), entry.getValue());
        }
    }

    private static void spillPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildSpillPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    private static void reloadPromotedSlots(MethodVisitor visitor, LoweredUnit unit, int[] slotIds) {
        for (int slotId : slotIds) {
            if (unit.isPromoted(slotId)) {
                BaselineBytecodeOps.buildReloadPromotedSlot(visitor, slotId, unit.localIndexFor(slotId));
            }
        }
    }

    public static boolean shouldInlineRequiredSlots(int slotCount, int callSites) {
        if (slotCount == 0) {
            return true;
        }
        if (callSites <= 1) {
            return true;
        }
        return 2 * slotCount * callSites - (3 * slotCount + 2 * callSites) < 10;
    }

    private static String sanitize(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    public record CompiledClassData(
            net.minecraft.util.Identifier id,
            String internalName,
            byte[] classBytes,
            int[] requiredSlots
    ) {
    }

    private record SpecializedFieldSpec(
            int specializedId,
            String fieldName,
            String planDescriptor,
            String planInternalName,
            String executorOwner,
            String executorMethod,
            String executorDescriptor
    ) {
    }
}
