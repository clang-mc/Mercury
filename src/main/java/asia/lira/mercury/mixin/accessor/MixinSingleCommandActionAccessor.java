package asia.lira.mercury.mixin.accessor;

import com.mojang.brigadier.context.ContextChain;
import net.minecraft.command.SingleCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SingleCommandAction.class)
public interface MixinSingleCommandActionAccessor<T extends AbstractServerCommandSource<T>> {
    @Accessor("command")
    String mercury$getCommand();

    @Accessor("contextChain")
    ContextChain<T> mercury$getContextChain();
}
