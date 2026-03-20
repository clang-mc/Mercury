package asia.lira.mercury.jit;

import asia.lira.mercury.Mercury;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.command.ReturnValueConsumer;
import net.minecraft.nbt.AbstractNbtNumber;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtLong;
import net.minecraft.nbt.NbtShort;
import net.minecraft.predicate.NumberRange;
import net.minecraft.scoreboard.ReadableScoreboardScore;
import net.minecraft.scoreboard.ScoreAccess;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class ExecuteRuntime {
    private ExecuteRuntime() {
    }

    public static void invoke(SpecializedCommandRegistry.ExecutePlan plan, ExecutionFrame frame, ServerCommandSource source) throws Exception {
        List<PendingStore> pendingStores = new ArrayList<>();
        for (SpecializedCommandRegistry.ExecuteModifier modifier : plan.modifiers()) {
            switch (modifier) {
                case SpecializedCommandRegistry.IfScoreCompareModifier compareModifier -> {
                    if (!testScoreCompare(frame, source, compareModifier)) {
                        return;
                    }
                }
                case SpecializedCommandRegistry.IfScoreMatchesModifier matchesModifier -> {
                    if (!testScoreMatches(frame, source, matchesModifier)) {
                        return;
                    }
                }
                case SpecializedCommandRegistry.StoreScoreModifier scoreModifier ->
                        pendingStores.add(new ScoreStore(scoreModifier.requestResult(), scoreModifier.holder(), scoreModifier.objective()));
                case SpecializedCommandRegistry.StoreStorageModifier storageModifier ->
                        pendingStores.add(new StorageStore(storageModifier.requestResult(), storageModifier.storageId(), storageModifier.path(), storageModifier.numericType(), storageModifier.scale()));
            }
        }

        CommandResult result = SpecializedRuntime.executeTerminal(plan.terminal(), frame, source);
        for (PendingStore pendingStore : pendingStores) {
            pendingStore.apply(frame, source, result);
        }
    }

    private static boolean testScoreCompare(
            ExecutionFrame frame,
            ServerCommandSource source,
            SpecializedCommandRegistry.IfScoreCompareModifier modifier
    ) {
        int left = SpecializedRuntime.readScore(frame, source, modifier.targetHolder(), modifier.targetObjective());
        int right = SpecializedRuntime.readScore(frame, source, modifier.sourceHolder(), modifier.sourceObjective());
        return switch (modifier.operator()) {
            case "=" -> left == right;
            case "<" -> left < right;
            case "<=" -> left <= right;
            case ">" -> left > right;
            case ">=" -> left >= right;
            default -> false;
        };
    }

    private static boolean testScoreMatches(
            ExecutionFrame frame,
            ServerCommandSource source,
            SpecializedCommandRegistry.IfScoreMatchesModifier modifier
    ) {
        int value = SpecializedRuntime.readScore(frame, source, modifier.targetHolder(), modifier.targetObjective());
        NumberRange.IntRange range = modifier.range();
        return range.test(value);
    }

    public record CommandResult(boolean successful, int returnValue) {
    }

    private sealed interface PendingStore permits ScoreStore, StorageStore {
        void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) throws Exception;
    }

    private record ScoreStore(boolean requestResult, String holder, String objective) implements PendingStore {
        @Override
        public void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) {
            int value = requestResult ? result.returnValue() : (result.successful() ? 1 : 0);
            SpecializedRuntime.writeScore(frame, source, holder, objective, value);
        }
    }

    private record StorageStore(
            boolean requestResult,
            Identifier storageId,
            NbtPathArgumentType.NbtPath path,
            String numericType,
            double scale
    ) implements PendingStore {
        @Override
        public void apply(ExecutionFrame frame, ServerCommandSource source, CommandResult result) throws Exception {
            DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
            net.minecraft.nbt.NbtCompound root = storage.get(storageId).copy();
            int value = requestResult ? result.returnValue() : (result.successful() ? 1 : 0);
            path.put(root, wrapNumber(value, numericType, scale));
            storage.set(storageId, root);
        }

        private static NbtElement wrapNumber(int value, String numericType, double scale) {
            double scaled = value * scale;
            return switch (numericType) {
                case "byte" -> NbtByte.of((byte) scaled);
                case "short" -> NbtShort.of((short) scaled);
                case "int" -> NbtInt.of((int) scaled);
                case "long" -> NbtLong.of((long) scaled);
                case "float" -> NbtFloat.of((float) scaled);
                case "double" -> NbtDouble.of(scaled);
                default -> NbtInt.of((int) scaled);
            };
        }
    }
}
