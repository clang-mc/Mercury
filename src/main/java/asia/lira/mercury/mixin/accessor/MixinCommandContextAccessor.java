package asia.lira.mercury.mixin.accessor;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.RedirectModifier;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedArgument;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.context.StringRange;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;

@Mixin(value = CommandContext.class, remap = false)
public interface MixinCommandContextAccessor<S> {
    @Accessor("input")
    String mercury$getInput();

    @Accessor("arguments")
    Map<String, ParsedArgument<S, ?>> mercury$getArguments();

    @Accessor("command")
    Command<S> mercury$getCommand();

    @Accessor("rootNode")
    CommandNode<S> mercury$getRootNode();

    @Accessor("nodes")
    List<ParsedCommandNode<S>> mercury$getNodes();

    @Accessor("range")
    StringRange mercury$getRange();

    @Accessor("child")
    CommandContext<S> mercury$getChild();

    @Accessor("modifier")
    RedirectModifier<S> mercury$getModifier();

    @Accessor("forks")
    boolean mercury$getForks();
}
