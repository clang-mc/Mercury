package asia.lira.mercury.jit;

import asia.lira.mercury.ir.FunctionIrRegistry;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BaselineCompiler {
    private static final Pattern SCOREBOARD_SET_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+set\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    private static final Pattern SCOREBOARD_ADD_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+add\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    private static final Pattern SCOREBOARD_REMOVE_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+remove\\s+(\\S+)\\s+(\\S+)\\s+(-?\\d+)$");
    private static final Pattern SCOREBOARD_GET_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+get\\s+(\\S+)\\s+(\\S+)$");
    private static final Pattern SCOREBOARD_RESET_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+reset\\s+(\\S+)\\s+(\\S+)$");
    private static final Pattern SCOREBOARD_OPERATION_PATTERN = Pattern.compile("^scoreboard\\s+players\\s+operation\\s+(\\S+)\\s+(\\S+)\\s+(=|\\+=|-=|\\*=|/=|%=|<|>|><)\\s+(\\S+)\\s+(\\S+)$");
    private static final Pattern RETURN_VALUE_PATTERN = Pattern.compile("^return\\s+(-?\\d+)$");

    private BaselineCompiler() {
    }

    public static @Nullable BaselineProgram.Builder analyze(FunctionIrRegistry.ParsedFunctionIr functionIr) {
        BaselineProgram.Builder builder = new BaselineProgram.Builder(functionIr.id());

        for (FunctionIrRegistry.ParseNode node : functionIr.nodes()) {
            if (!(node instanceof FunctionIrRegistry.CommandParseNode commandNode)) {
                return null;
            }

            if (!appendInstruction(builder, commandNode)) {
                return null;
            }
        }

        return builder;
    }

    private static boolean appendInstruction(BaselineProgram.Builder builder, FunctionIrRegistry.CommandParseNode commandNode) {
        String sourceText = commandNode.sourceText();

        if (commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.FUNCTION) {
            if (commandNode.targetFunctionId() == null) {
                return false;
            }
            builder.addDependency(commandNode.targetFunctionId());
            builder.addInstruction(BaselineInstruction.call(commandNode.targetFunctionId(), sourceText));
            return true;
        }

        if (commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.RETURN_RUN_FUNCTION
                || commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.EXECUTE_RUN_FUNCTION
                || commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.EXECUTE) {
            return false;
        }

        if (commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.RETURN) {
            Matcher matcher = RETURN_VALUE_PATTERN.matcher(sourceText);
            if (!matcher.matches()) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.returnValue(Integer.parseInt(matcher.group(1)), sourceText));
            return true;
        }

        if (commandNode.controlFlowKind() == FunctionIrRegistry.ControlFlowKind.NONE
                && commandNode.targetFunctionId() == null
                && commandNode.binding().rootPath().isEmpty()) {
            return false;
        }

        Matcher matcher = SCOREBOARD_SET_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            if (slotId == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.set(slotId, Integer.parseInt(matcher.group(3)), sourceText));
            return true;
        }

        matcher = SCOREBOARD_ADD_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            if (slotId == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.add(slotId, Integer.parseInt(matcher.group(3)), sourceText));
            return true;
        }

        matcher = SCOREBOARD_REMOVE_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            if (slotId == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.add(slotId, -Integer.parseInt(matcher.group(3)), sourceText));
            return true;
        }

        matcher = SCOREBOARD_GET_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            if (slotId == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.get(slotId, sourceText));
            return true;
        }

        matcher = SCOREBOARD_RESET_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer slotId = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            if (slotId == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.reset(slotId, sourceText));
            return true;
        }

        matcher = SCOREBOARD_OPERATION_PATTERN.matcher(sourceText);
        if (matcher.matches()) {
            Integer targetSlot = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(1), matcher.group(2));
            Integer sourceSlot = JitPreparationRegistry.getInstance().slotRegistry().getSlotId(matcher.group(4), matcher.group(5));
            if (targetSlot == null || sourceSlot == null) {
                return false;
            }
            builder.addInstruction(BaselineInstruction.operation(targetSlot, sourceSlot, matcher.group(3), sourceText));
            return true;
        }

        return false;
    }
}
