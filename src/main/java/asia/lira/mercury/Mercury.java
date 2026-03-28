package asia.lira.mercury;

import asia.lira.mercury.command.CommandHandler;
import asia.lira.mercury.jit.runtime.SynchronizationRuntime;
import asia.lira.mercury.stat.JMXIntegration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public class Mercury implements ModInitializer {
    public static final int API_VERSION = 1;
    public static MinecraftServer SERVER;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(new CommandHandler());
        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SynchronizationRuntime.getInstance().clear());

        JMXIntegration.initialize();
    }

    private void onServerStarting(MinecraftServer server) {
        SERVER = server;
    }
}
