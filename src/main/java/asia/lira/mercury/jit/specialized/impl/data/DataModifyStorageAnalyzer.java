package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.jit.specialized.api.SpecializationAnalyzer;
import asia.lira.mercury.jit.specialized.api.SpecializedPlan;
import com.mojang.brigadier.StringReader;
import net.minecraft.command.argument.NbtPathArgumentType;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DataModifyStorageAnalyzer implements SpecializationAnalyzer {
    private static final Pattern SET_VALUE = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+set\\s+value\\s+(.+)$");
    private static final Pattern SET_FROM = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+set\\s+from\\s+storage\\s+(\\S+)\\s+(\\S+)$");
    private static final Pattern MERGE_VALUE = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+merge\\s+value\\s+(.+)$");
    private static final Pattern MERGE_FROM = Pattern.compile("^data\\s+modify\\s+storage\\s+(\\S+)\\s+(\\S+)\\s+merge\\s+from\\s+storage\\s+(\\S+)\\s+(\\S+)$");

    @Override
    public @Nullable SpecializedPlan analyze(String sourceText) {
        Matcher matcher = SET_VALUE.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.SET_VALUE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        StringNbtReader.parse(matcher.group(3)),
                        null,
                        null
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = SET_FROM.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.SET_FROM_STORAGE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        null,
                        Identifier.of(matcher.group(3)),
                        parsePath(matcher.group(4))
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = MERGE_VALUE.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.MERGE_VALUE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        StringNbtReader.parse(matcher.group(3)),
                        null,
                        null
                );
            } catch (Exception exception) {
                return null;
            }
        }

        matcher = MERGE_FROM.matcher(sourceText);
        if (matcher.matches()) {
            try {
                return new DataModifyStoragePlan(
                        sourceText,
                        DataModifyStoragePlan.Operation.MERGE_FROM_STORAGE,
                        Identifier.of(matcher.group(1)),
                        parsePath(matcher.group(2)),
                        null,
                        Identifier.of(matcher.group(3)),
                        parsePath(matcher.group(4))
                );
            } catch (Exception exception) {
                return null;
            }
        }

        return null;
    }

    private static NbtPathArgumentType.NbtPath parsePath(String expression) throws Exception {
        return NbtPathArgumentType.nbtPath().parse(new StringReader(expression));
    }
}
