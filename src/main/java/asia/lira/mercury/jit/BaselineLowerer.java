package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BaselineLowerer {
    private BaselineLowerer() {
    }

    public static LoweredUnit lower(
            Identifier entryId,
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            int[] requiredSlots
    ) {
        List<LoweredUnit.LoweredBlock> blocks = new ArrayList<>(unit.programs().size());
        for (BaselineProgram program : unit.programs()) {
            blocks.add(lowerBlock(unit, program));
        }
        return new LoweredUnit(entryId, blocks, unit.entryIndex(), requiredSlots, Map.of());
    }

    private static LoweredUnit.LoweredBlock lowerBlock(
            BaselineCompiledFunctionRegistry.JumpGraph.CompilationUnit unit,
            BaselineProgram program
    ) {
        List<LoweredUnit.LoweredInstruction> instructions = new ArrayList<>();
        LoweredUnit.LoweredTerminator terminator = new LoweredUnit.CompleteTerminator();

        for (BaselineInstruction instruction : program.instructions()) {
            switch (instruction.opCode()) {
                case SET_CONST -> instructions.add(new LoweredUnit.SetConstInstruction(
                        instruction.primarySlot(),
                        instruction.immediate(),
                        instruction.sourceText()
                ));
                case ADD_CONST -> instructions.add(new LoweredUnit.AddConstInstruction(
                        instruction.primarySlot(),
                        instruction.immediate(),
                        instruction.sourceText()
                ));
                case GET -> instructions.add(new LoweredUnit.GetInstruction(
                        instruction.primarySlot(),
                        instruction.sourceText()
                ));
                case RESET -> instructions.add(new LoweredUnit.ResetInstruction(
                        instruction.primarySlot(),
                        instruction.sourceText()
                ));
                case OPERATION -> instructions.add(new LoweredUnit.OperationInstruction(
                        instruction.primarySlot(),
                        instruction.secondarySlot(),
                        instruction.operation(),
                        instruction.sourceText()
                ));
                case CALL -> instructions.add(new LoweredUnit.CallInstruction(
                        instruction.targetFunction(),
                        new int[0],
                        new int[0],
                        instruction.sourceText()
                ));
                case JUMP -> {
                    int localIndex = unit.indexOf(instruction.targetFunction());
                    terminator = localIndex >= 0
                            ? new LoweredUnit.JumpLocalTerminator(localIndex)
                            : new LoweredUnit.JumpExternalTerminator(instruction.targetFunction(), new int[0]);
                }
                case RETURN_VALUE -> terminator = new LoweredUnit.ReturnValueTerminator(instruction.immediate());
            }
        }

        return new LoweredUnit.LoweredBlock(program.id(), instructions, terminator);
    }
}
