package asia.lira.mercury.jit;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.SourcedCommandAction;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.CommandFunction;
import net.minecraft.server.function.MacroException;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CompiledFunctionWrapper<T extends AbstractServerCommandSource<T>> implements CommandFunction<T> {
    private final Identifier id;
    private final Procedure<T> fallback;

    public CompiledFunctionWrapper(Identifier id, Procedure<T> fallback) {
        this.id = id;
        this.fallback = fallback;
    }

    @Override
    public Identifier id() {
        return id;
    }

    @Override
    public Procedure<T> withMacroReplaced(@Nullable NbtCompound arguments, CommandDispatcher<T> dispatcher) throws MacroException {
        BaselineProgram program = BaselineCompiledFunctionRegistry.getInstance().get(id);
        if (program == null) {
            return fallback;
        }

        SourcedCommandAction<T> action = new BaselineCompiledAction<>(program, BaselineCompiler.analyze(
                asia.lira.mercury.ir.FunctionIrRegistry.getInstance().getParsed(id).orElseThrow()
        ).build());
        return new BaselineCompiledProcedure<>(id, List.of(action));
    }
}
