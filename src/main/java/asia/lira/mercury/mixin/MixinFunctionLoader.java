package asia.lira.mercury.mixin;

import asia.lira.mercury.jit.registry.GlobalCompilationCoordinator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableMap.Builder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagGroupLoader;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.CommandFunctionManager;
import net.minecraft.server.function.FunctionLoader;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.Map.Entry;

@Mixin(FunctionLoader.class)
public abstract class MixinFunctionLoader {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private static ResourceFinder FINDER;
    @Shadow private volatile Map<Identifier, CommandFunction<ServerCommandSource>> functions;
    @Shadow @Final private TagGroupLoader<CommandFunction<ServerCommandSource>> tagLoader;
    @Shadow private volatile Map<Identifier, List<CommandFunction<ServerCommandSource>>> tags;
    @Shadow @Final private int level;
    @Shadow @Final private com.mojang.brigadier.CommandDispatcher<ServerCommandSource> commandDispatcher;

    @Shadow
    public abstract Optional<CommandFunction<ServerCommandSource>> get(Identifier id);

    /**
     * @author Codex
     * @reason establish global compilation context at function-load time before per-function parsing
     */
    @Overwrite
    public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer, ResourceManager manager, Executor prepareExecutor, Executor applyExecutor) {
        CompletableFuture<Map<Identifier, List<TagGroupLoader.TrackedEntry>>> tagsFuture = CompletableFuture.supplyAsync(
                () -> this.tagLoader.loadTags(manager), prepareExecutor
        );
        CompletableFuture<PreparedReload> functionsFuture = CompletableFuture
                .<Map<Identifier, Resource>>supplyAsync(() -> FINDER.findResources(manager), prepareExecutor)
                .thenCompose(resources -> {
                    Map<Identifier, List<String>> rawSources = new LinkedHashMap<>();
                    Map<Identifier, CompletableFuture<CommandFunction<ServerCommandSource>>> functionFutures = Maps.newHashMap();
                    ServerCommandSource source = new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO, null, this.level, "", ScreenTexts.EMPTY, null, null);

                    for (Entry<Identifier, Resource> resourceEntry : resources.entrySet()) {
                        Identifier resourceId = resourceEntry.getKey();
                        Identifier functionId = FINDER.toResourceId(resourceId);
                        List<String> lines = mercury$readLines(resourceEntry.getValue());
                        rawSources.put(functionId, lines);
                    }

                    GlobalCompilationCoordinator.getInstance().beginReload(rawSources);

                    for (Entry<Identifier, List<String>> sourceEntry : rawSources.entrySet()) {
                        Identifier functionId = sourceEntry.getKey();
                        List<String> lines = sourceEntry.getValue();
                        functionFutures.put(functionId, CompletableFuture.supplyAsync(
                                () -> CommandFunction.create(functionId, this.commandDispatcher, source, lines),
                                prepareExecutor
                        ));
                    }
                    CompletableFuture<?>[] all = functionFutures.values().toArray(new CompletableFuture[0]);
                    return CompletableFuture.allOf(all).handle((unused, ex) -> new PreparedReload(rawSources, functionFutures));
                });

        return tagsFuture.thenCombine(functionsFuture, Pair::of)
                .thenCompose(synchronizer::whenPrepared)
                .thenAcceptAsync(intermediate -> {
                    PreparedReload preparedReload = (PreparedReload) intermediate.getSecond();
                    Builder<Identifier, CommandFunction<ServerCommandSource>> builder = ImmutableMap.builder();
                    preparedReload.functionFutures().forEach((id, functionFuture) -> functionFuture.handle((function, ex) -> {
                        if (ex != null) {
                            LOGGER.error("Failed to load function {}", id, ex);
                        } else {
                            builder.put(id, function);
                        }
                        return null;
                    }).join());

                    Map<Identifier, CommandFunction<ServerCommandSource>> loaded = builder.build();
                    this.functions = GlobalCompilationCoordinator.getInstance().finishReload(loaded);
                    this.tags = this.tagLoader.buildGroup((Map<Identifier, List<TagGroupLoader.TrackedEntry>>) intermediate.getFirst());
                }, applyExecutor);
    }

    private static List<String> mercury$readLines(Resource resource) {
        try {
            try (BufferedReader reader = resource.getReader()) {
                return reader.lines().toList();
            }
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private record PreparedReload(
            Map<Identifier, List<String>> rawSources,
            Map<Identifier, CompletableFuture<CommandFunction<ServerCommandSource>>> functionFutures
    ) {
    }
}
