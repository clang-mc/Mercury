package asia.lira.mercury.mixin.accessor;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.command.MacroInvocation;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.Macro;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Macro.VariableLine.class)
public interface MixinMacroVariableLineAccessor<T extends AbstractServerCommandSource<T>> {
    @Accessor("invocation")
    MacroInvocation mercury$getInvocation();

    @Accessor("variableIndices")
    IntList mercury$getVariableIndices();
}
