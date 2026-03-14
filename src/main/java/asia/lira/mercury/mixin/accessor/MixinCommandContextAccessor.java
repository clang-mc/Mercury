package asia.lira.mercury.mixin.accessor;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedArgument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = CommandContext.class, remap = false)
public interface MixinCommandContextAccessor<S> {
    @Accessor("arguments")
    Map<String, ParsedArgument<S, ?>> mercury$getArguments();
}
