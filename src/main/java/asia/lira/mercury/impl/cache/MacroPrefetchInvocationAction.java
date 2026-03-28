package asia.lira.mercury.impl.cache;

import net.minecraft.command.CommandExecutionContext;
import net.minecraft.command.Frame;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;

public final class MacroPrefetchInvocationAction<T extends AbstractServerCommandSource<T>> implements SourcedCommandAction<T> {
    private final int planId;

    public MacroPrefetchInvocationAction(int planId) {
        this.planId = planId;
    }

    @Override
    public void execute(T source, CommandExecutionContext<T> context, Frame frame) {
        try {
            MacroPrefetchRuntime.invokePrefetchedMacro(planId, source, context, frame);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to invoke prefetched macro plan " + planId, exception);
        }
    }
}
