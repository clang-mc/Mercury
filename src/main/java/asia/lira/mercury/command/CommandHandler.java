package asia.lira.mercury.command;

import asia.lira.mercury.Mercury;
import asia.lira.mercury.ir.FunctionIrDumper;
import asia.lira.mercury.ir.FunctionIrExporter;
import asia.lira.mercury.ir.FunctionIrRegistry;
import asia.lira.mercury.jit.BaselineClassExporter;
import asia.lira.mercury.jit.JitPreparationExporter;
import asia.lira.mercury.jit.MercuryJitRuntime;
import asia.lira.mercury.jit.SynchronizationRuntime;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.DataCommandStorage;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.scoreboard.*;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ReloadCommand;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static net.minecraft.server.command.CommandManager.literal;


public class CommandHandler implements CommandRegistrationCallback {
    public static final ScoreHolder RAX = ScoreHolder.fromName("rax");
    public static final ScoreHolder R0 = ScoreHolder.fromName("r0");
    public static final Identifier STORAGE = Identifier.of("std", "vm");

    private static NbtList getHeap() {
        DataCommandStorage storage = Mercury.SERVER.getDataCommandStorage();
        return storage.get(STORAGE).getList("heap", NbtElement.INT_TYPE);
    }

    private static int sendLines(ServerCommandSource source, List<String> lines) {
        for (String line : lines) {
            source.sendFeedback(() -> Text.literal(line), false);
        }
        return lines.size();
    }

    private static int dumpRegistry(ServerCommandSource source) {
        FunctionIrRegistry registry = FunctionIrRegistry.getInstance();
        return sendLines(source, FunctionIrDumper.dumpRegistrySummary(registry, 20));
    }

    private static int dumpAll(ServerCommandSource source) {
        try {
            FunctionIrRegistry registry = FunctionIrRegistry.getInstance();
            FunctionIrExporter.ExportResult parsedResult = FunctionIrExporter.exportParsed(
                    registry,
                    source.getServer().getRunDirectory()
            );
            FunctionIrExporter.ExportResult semanticResult = FunctionIrExporter.exportSemantic(
                    registry,
                    source.getServer().getRunDirectory()
            );
            JitPreparationExporter.ExportResult preparedResult = JitPreparationExporter.exportPrepared(
                    source.getServer().getRunDirectory()
            );
            BaselineClassExporter.ExportResult classResult = BaselineClassExporter.exportClasses(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported parsed=" + parsedResult.exportedCount()
                            + " to " + parsedResult.outputDirectory()
                            + ", semantic=" + semanticResult.exportedCount()
                            + " to " + semanticResult.outputDirectory()
                            + ", prepared=" + preparedResult.exportedCount()
                            + " to " + preparedResult.outputDirectory()
                            + ", classes=" + classResult.exportedCount()
                            + " to " + classResult.outputDirectory()
            ), false);
            return parsedResult.exportedCount() + semanticResult.exportedCount() + preparedResult.exportedCount() + classResult.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpParsed(ServerCommandSource source) {
        try {
            FunctionIrExporter.ExportResult result = FunctionIrExporter.exportParsed(
                    FunctionIrRegistry.getInstance(),
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " parsed IR files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export parsed IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpSemantic(ServerCommandSource source) {
        try {
            FunctionIrExporter.ExportResult result = FunctionIrExporter.exportSemantic(
                    FunctionIrRegistry.getInstance(),
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " semantic IR files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export semantic IR: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpPrepared(ServerCommandSource source) {
        try {
            JitPreparationExporter.ExportResult result = JitPreparationExporter.exportPrepared(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " prepared JIT files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export prepared JIT: " + e.getMessage()));
            return 0;
        }
    }

    private static int dumpClasses(ServerCommandSource source) {
        try {
            BaselineClassExporter.ExportResult result = BaselineClassExporter.exportClasses(
                    source.getServer().getRunDirectory()
            );
            source.sendFeedback(() -> Text.literal(
                    "Exported " + result.exportedCount() + " generated class files to " + result.outputDirectory()
            ), false);
            return result.exportedCount();
        } catch (Exception e) {
            source.sendError(Text.literal("Failed to export generated classes: " + e.getMessage()));
            return 0;
        }
    }

    private static int switchJit(ServerCommandSource source, boolean enabled) {
        if (SynchronizationRuntime.getInstance().hasActiveFrames()) {
            source.sendError(Text.literal(
                    enabled
                            ? "Cannot enable Mercury JIT while a compiled frame is active"
                            : "Cannot disable Mercury JIT while a compiled frame is active"
            ));
            return -1;
        }

        if (MercuryJitRuntime.isEnabled() == enabled) {
            source.sendFeedback(() -> Text.literal(
                    enabled ? "Mercury JIT is already enabled" : "Mercury JIT is already disabled"
            ), false);
            return 1;
        }

        MercuryJitRuntime.setEnabled(enabled);
        ReloadCommand.tryReloadDataPacks(source.getServer().getDataPackManager().getEnabledIds(), source);
        source.sendFeedback(() -> Text.literal(
                enabled
                        ? "Mercury JIT enabled; reloading datapacks"
                        : "Mercury JIT disabled; reloading datapacks"
        ), true);
        return 1;
    }

    @Override
    public void register(@NotNull CommandDispatcher<ServerCommandSource> dispatcher,
                         CommandRegistryAccess access, CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("mercury")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("dump")
                        .executes(context -> dumpAll(context.getSource()))
                        .then(literal("parsed")
                                .executes(context -> dumpParsed(context.getSource()))
                        )
                        .then(literal("semantic")
                                .executes(context -> dumpSemantic(context.getSource()))
                        )
                        .then(literal("prepared")
                                .executes(context -> dumpPrepared(context.getSource()))
                        )
                        .then(literal("classes")
                                .executes(context -> dumpClasses(context.getSource()))
                        )
                )
                .then(literal("jit")
                        .then(literal("enable")
                                .executes(context -> switchJit(context.getSource(), true))
                        )
                        .then(literal("disable")
                                .executes(context -> switchJit(context.getSource(), false))
                        )
                )
        );

        dispatcher.register(literal("syscall")
                .executes(context -> {
                    ServerScoreboard scoreboard = Mercury.SERVER.getScoreboard();
                    ScoreboardObjective vmRegs = scoreboard.getNullableObjective("vm_regs");
                    ScoreboardScore rax = (ScoreboardScore) scoreboard.getScore(RAX, vmRegs);
                    if (rax == null) {
                        return -1;
                    }
                    int id = rax.getScore();

                    switch (id) {
                        case 0 -> {  // int getAPIVersion()
                            rax.setScore(Mercury.API_VERSION);
                            return 1;
                        }
                        case 1 -> {  // void nanoTimes(Int64 *result)
                            ReadableScoreboardScore r0 = scoreboard.getScore(R0, vmRegs);
                            if (r0 == null) {
                                return -1;
                            }
                            NbtList heap = getHeap();
                            // typedef struct {
                            //     int low;
                            //     int high;
                            // } Int64;
                            int addr = r0.getScore();
                            long value = System.nanoTime();
                            int low = (int) (value & 0xFFFFFFFFL);
                            int high = (int) ((value >>> 32) & 0xFFFFFFFFL);
                            heap.setElement(addr, NbtInt.of(low));
                            heap.setElement(addr + 1, NbtInt.of(high));
                            return 1;
                        }
                        case 2 -> {  // void puts(const char *string)
                            ReadableScoreboardScore r0 = scoreboard.getScore(R0, vmRegs);
                            if (r0 == null) {
                                return -1;
                            }
                            NbtList heap = getHeap();
                            int addr = r0.getScore();
                            StringBuilder builder = new StringBuilder();
                            char c;
                            while ((c = (char) heap.getInt(addr)) != 0) {
                                builder.append(c);
                                addr++;
                            }
                            Mercury.SERVER.getPlayerManager().broadcast(Text.literal(
                                    builder.toString()
                            ), false);
                            return 1;
                        }
                        default -> {
                            return -1;
                        }
                    }
                })
        );
    }
}
