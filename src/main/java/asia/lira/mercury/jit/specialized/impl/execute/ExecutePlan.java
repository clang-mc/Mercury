package asia.lira.mercury.jit.specialized.impl.execute;

import asia.lira.mercury.jit.specialized.api.SpecializedEmitContext;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import asia.lira.mercury.jit.specialized.impl.data.StorageAccessRuntime;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.predicate.NumberRange;
import net.minecraft.util.Identifier;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.List;

public record ExecutePlan(
        String sourceText,
        List<ExecuteModifier> modifiers,
        ExecuteTerminal terminal
) implements SpecializedPlan {
    private static final int SUCCESS_LOCAL = 5;
    private static final int RESULT_LOCAL = 6;
    private static final int SCRATCH_LOCAL = 7;

    @Override
    public void emitBytecode(SpecializedEmitContext context) {
        Label skipLabel = new Label();
        for (ExecuteModifier modifier : modifiers) {
            emitModifier(context, modifier, skipLabel);
        }

        emitTerminal(context, terminal);
        for (ExecuteModifier modifier : modifiers) {
            emitStore(context, modifier);
        }
        context.visitor().visitLabel(skipLabel);
    }

    private void emitModifier(SpecializedEmitContext context, ExecuteModifier modifier, Label skipLabel) {
        switch (modifier) {
            case IfScoreCompareModifier compareModifier -> emitIfScoreCompare(context, compareModifier, skipLabel);
            case IfScoreMatchesModifier matchesModifier -> emitIfScoreMatches(context, matchesModifier, skipLabel);
            case StoreScoreModifier ignored -> {
            }
            case StoreStorageModifier ignored -> {
            }
        }
    }

    private void emitIfScoreCompare(SpecializedEmitContext context, IfScoreCompareModifier modifier, Label skipLabel) {
        emitReadScoreLiteral(context, modifier.targetHolder(), modifier.targetObjective());
        emitReadScoreLiteral(context, modifier.sourceHolder(), modifier.sourceObjective());
        int opcode = switch (modifier.operator()) {
            case "=" -> Opcodes.IF_ICMPNE;
            case "<" -> Opcodes.IF_ICMPGE;
            case "<=" -> Opcodes.IF_ICMPGT;
            case ">" -> Opcodes.IF_ICMPLE;
            case ">=" -> Opcodes.IF_ICMPLT;
            default -> throw new IllegalArgumentException("Unsupported execute score operator " + modifier.operator());
        };
        context.visitor().visitJumpInsn(opcode, skipLabel);
    }

    private void emitIfScoreMatches(SpecializedEmitContext context, IfScoreMatchesModifier modifier, Label skipLabel) {
        emitReadScoreLiteral(context, modifier.targetHolder(), modifier.targetObjective());
        context.visitor().visitVarInsn(Opcodes.ISTORE, SCRATCH_LOCAL);
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "modifiers",
                "()Ljava/util/List;",
                false
        );
        context.pushInt(modifiers.indexOf(modifier));
        context.visitor().visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(IfScoreMatchesModifier.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(IfScoreMatchesModifier.class),
                "range",
                "()" + Type.getDescriptor(NumberRange.IntRange.class),
                false
        );
        context.visitor().visitVarInsn(Opcodes.ILOAD, SCRATCH_LOCAL);
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(NumberRange.IntRange.class),
                "test",
                "(I)Z",
                false
        );
        context.visitor().visitJumpInsn(Opcodes.IFEQ, skipLabel);
    }

    private void emitTerminal(SpecializedEmitContext context, ExecuteTerminal executeTerminal) {
        switch (executeTerminal) {
            case ScoreTerminalPlan scoreTerminalPlan -> emitScoreTerminal(context, scoreTerminalPlan);
            case DataStorageTerminalPlan dataStorageTerminalPlan -> emitDataTerminal(context, dataStorageTerminalPlan);
        }
    }

    private void emitScoreTerminal(SpecializedEmitContext context, ScoreTerminalPlan plan) {
        switch (plan.operation()) {
            case SET -> {
                emitWriteScoreLiteral(context, plan.targetHolder(), plan.targetObjective(), plan.value());
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
                context.pushInt(plan.value());
                context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
            }
            case ADD -> {
                emitReadScoreLiteral(context, plan.targetHolder(), plan.targetObjective());
                context.pushInt(plan.value());
                context.visitor().visitInsn(Opcodes.IADD);
                context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
                emitWriteScoreFromLocal(context, plan.targetHolder(), plan.targetObjective(), RESULT_LOCAL);
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
            }
            case GET -> {
                emitReadScoreLiteral(context, plan.targetHolder(), plan.targetObjective());
                context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
            }
            case RESET -> {
                context.loadFrame();
                context.loadSourceAsServerCommandSource();
                context.visitor().visitLdcInsn(plan.targetHolder());
                context.visitor().visitLdcInsn(plan.targetObjective());
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(ExecuteExecutor.class),
                        "resetScore",
                        "(" + Type.getDescriptor(asia.lira.mercury.jit.ExecutionFrame.class)
                                + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class)
                                + Type.getDescriptor(String.class)
                                + Type.getDescriptor(String.class)
                                + ")V",
                        false
                );
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
            }
            case SCORE_OPERATION -> {
                emitReadScoreLiteral(context, plan.targetHolder(), plan.targetObjective());
                context.visitor().visitVarInsn(Opcodes.ISTORE, SCRATCH_LOCAL);
                context.visitor().visitVarInsn(Opcodes.ILOAD, SCRATCH_LOCAL);
                emitReadScoreLiteral(context, plan.sourceHolder(), plan.sourceObjective());
                context.visitor().visitLdcInsn(plan.scoreboardOperation());
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(ExecuteExecutor.class),
                        "applyOperation",
                        "(IILjava/lang/String;)I",
                        false
                );
                context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
                emitWriteScoreFromLocal(context, plan.targetHolder(), plan.targetObjective(), RESULT_LOCAL);
                if ("><".equals(plan.scoreboardOperation())) {
                    emitWriteScoreFromLocal(context, plan.sourceHolder(), plan.sourceObjective(), SCRATCH_LOCAL);
                }
                context.pushInt(1);
                context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
            }
        }
    }

    private void emitDataTerminal(SpecializedEmitContext context, DataStorageTerminalPlan plan) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "terminal",
                "()" + Type.getDescriptor(ExecuteTerminal.class),
                false
        );
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DataStorageTerminalPlan.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataStorageTerminalPlan.class),
                "targetStorageId",
                "()" + Type.getDescriptor(Identifier.class),
                false
        );
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "terminal",
                "()" + Type.getDescriptor(ExecuteTerminal.class),
                false
        );
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DataStorageTerminalPlan.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataStorageTerminalPlan.class),
                "targetPath",
                "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                false
        );
        switch (plan.operation()) {
            case SET_VALUE -> {
                loadTerminalValue(context);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "setValue",
                        "(" + Type.getDescriptor(Identifier.class) + Type.getDescriptor(NbtPathArgumentType.NbtPath.class) + Type.getDescriptor(NbtElement.class) + ")I",
                        false
                );
            }
            case SET_FROM_STORAGE -> {
                loadTerminalSourceStorage(context);
                loadTerminalSourcePath(context);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "setFromStorage",
                        "(" + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + ")I",
                        false
                );
            }
            case MERGE_VALUE -> {
                loadTerminalValue(context);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "mergeValue",
                        "(" + Type.getDescriptor(Identifier.class) + Type.getDescriptor(NbtPathArgumentType.NbtPath.class) + Type.getDescriptor(NbtElement.class) + ")I",
                        false
                );
            }
            case MERGE_FROM_STORAGE -> {
                loadTerminalSourceStorage(context);
                loadTerminalSourcePath(context);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "mergeFromStorage",
                        "(" + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + ")I",
                        false
                );
            }
        }
        context.visitor().visitVarInsn(Opcodes.ISTORE, RESULT_LOCAL);
        context.pushInt(1);
        context.visitor().visitVarInsn(Opcodes.ISTORE, SUCCESS_LOCAL);
    }

    private void emitStore(SpecializedEmitContext context, ExecuteModifier modifier) {
        switch (modifier) {
            case StoreScoreModifier storeScoreModifier -> {
                context.loadFrame();
                context.loadSourceAsServerCommandSource();
                context.visitor().visitLdcInsn(storeScoreModifier.holder());
                context.visitor().visitLdcInsn(storeScoreModifier.objective());
                context.visitor().visitVarInsn(Opcodes.ILOAD, storeScoreModifier.requestResult() ? RESULT_LOCAL : SUCCESS_LOCAL);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(ExecuteExecutor.class),
                        "writeScore",
                        "(" + Type.getDescriptor(asia.lira.mercury.jit.ExecutionFrame.class)
                                + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class)
                                + Type.getDescriptor(String.class)
                                + Type.getDescriptor(String.class)
                                + "I)V",
                        false
                );
            }
            case StoreStorageModifier storeStorageModifier -> {
                loadStoreStoragePath(context, storeStorageModifier);
                context.visitor().visitLdcInsn(storeStorageModifier.numericType());
                context.visitor().visitLdcInsn(storeStorageModifier.scale());
                context.visitor().visitVarInsn(Opcodes.ILOAD, storeStorageModifier.requestResult() ? RESULT_LOCAL : SUCCESS_LOCAL);
                context.visitor().visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        Type.getInternalName(StorageAccessRuntime.class),
                        "writeNumericValue",
                        "(" + Type.getDescriptor(Identifier.class)
                                + Type.getDescriptor(NbtPathArgumentType.NbtPath.class)
                                + Type.getDescriptor(String.class)
                                + "DI)V",
                        false
                );
            }
            default -> {
            }
        }
    }

    private void emitReadScoreLiteral(SpecializedEmitContext context, String holder, String objective) {
        context.loadFrame();
        context.loadSourceAsServerCommandSource();
        context.visitor().visitLdcInsn(holder);
        context.visitor().visitLdcInsn(objective);
        context.visitor().visitMethodInsn(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(ExecuteExecutor.class),
                "readScore",
                "(" + Type.getDescriptor(asia.lira.mercury.jit.ExecutionFrame.class)
                        + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class)
                        + Type.getDescriptor(String.class)
                        + Type.getDescriptor(String.class)
                        + ")I",
                false
        );
    }

    private void emitWriteScoreLiteral(SpecializedEmitContext context, String holder, String objective, int value) {
        context.loadFrame();
        context.loadSourceAsServerCommandSource();
        context.visitor().visitLdcInsn(holder);
        context.visitor().visitLdcInsn(objective);
        context.pushInt(value);
        context.visitor().visitMethodInsn(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(ExecuteExecutor.class),
                "writeScore",
                "(" + Type.getDescriptor(asia.lira.mercury.jit.ExecutionFrame.class)
                        + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class)
                        + Type.getDescriptor(String.class)
                        + Type.getDescriptor(String.class)
                        + "I)V",
                false
        );
    }

    private void emitWriteScoreFromLocal(SpecializedEmitContext context, String holder, String objective, int localIndex) {
        context.loadFrame();
        context.loadSourceAsServerCommandSource();
        context.visitor().visitLdcInsn(holder);
        context.visitor().visitLdcInsn(objective);
        context.visitor().visitVarInsn(Opcodes.ILOAD, localIndex);
        context.visitor().visitMethodInsn(
                Opcodes.INVOKESTATIC,
                Type.getInternalName(ExecuteExecutor.class),
                "writeScore",
                "(" + Type.getDescriptor(asia.lira.mercury.jit.ExecutionFrame.class)
                        + Type.getDescriptor(net.minecraft.server.command.ServerCommandSource.class)
                        + Type.getDescriptor(String.class)
                        + Type.getDescriptor(String.class)
                        + "I)V",
                false
        );
    }

    private void loadTerminalValue(SpecializedEmitContext context) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "terminal",
                "()" + Type.getDescriptor(ExecuteTerminal.class),
                false
        );
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DataStorageTerminalPlan.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataStorageTerminalPlan.class),
                "value",
                "()" + Type.getDescriptor(NbtElement.class),
                false
        );
    }

    private void loadTerminalSourceStorage(SpecializedEmitContext context) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "terminal",
                "()" + Type.getDescriptor(ExecuteTerminal.class),
                false
        );
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DataStorageTerminalPlan.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataStorageTerminalPlan.class),
                "sourceStorageId",
                "()" + Type.getDescriptor(Identifier.class),
                false
        );
    }

    private void loadTerminalSourcePath(SpecializedEmitContext context) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "terminal",
                "()" + Type.getDescriptor(ExecuteTerminal.class),
                false
        );
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(DataStorageTerminalPlan.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(DataStorageTerminalPlan.class),
                "sourcePath",
                "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                false
        );
    }

    private void loadStoreStoragePath(SpecializedEmitContext context, StoreStorageModifier storeStorageModifier) {
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "modifiers",
                "()Ljava/util/List;",
                false
        );
        context.pushInt(modifiers.indexOf(storeStorageModifier));
        context.visitor().visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(StoreStorageModifier.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(StoreStorageModifier.class),
                "storageId",
                "()" + Type.getDescriptor(Identifier.class),
                false
        );
        context.loadPlan();
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(ExecutePlan.class),
                "modifiers",
                "()Ljava/util/List;",
                false
        );
        context.pushInt(modifiers.indexOf(storeStorageModifier));
        context.visitor().visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get", "(I)Ljava/lang/Object;", true);
        context.visitor().visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(StoreStorageModifier.class));
        context.visitor().visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                Type.getInternalName(StoreStorageModifier.class),
                "path",
                "()" + Type.getDescriptor(NbtPathArgumentType.NbtPath.class),
                false
        );
    }
}
