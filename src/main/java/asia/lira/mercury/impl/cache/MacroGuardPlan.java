package asia.lira.mercury.impl.cache;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;

import java.util.List;

public record MacroGuardPlan(
        List<String> argumentNames,
        NbtCompound expectedArguments
) {
    public MacroGuardPlan {
        argumentNames = List.copyOf(argumentNames);
        expectedArguments = expectedArguments.copy();
    }

    public boolean matches(NbtCompound actualArguments) {
        for (String argumentName : argumentNames) {
            NbtElement expected = expectedArguments.get(argumentName);
            NbtElement actual = actualArguments.get(argumentName);
            if (expected == null || actual == null || !expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    public String signature() {
        StringBuilder builder = new StringBuilder();
        for (String argumentName : argumentNames) {
            builder.append(argumentName).append('=').append(expectedArguments.get(argumentName)).append(';');
        }
        return builder.toString();
    }
}
