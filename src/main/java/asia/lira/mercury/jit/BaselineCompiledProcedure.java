package asia.lira.mercury.jit;

import net.minecraft.command.SourcedCommandAction;
import net.minecraft.server.command.AbstractServerCommandSource;
import net.minecraft.server.function.Procedure;
import net.minecraft.util.Identifier;

import java.util.List;

public record BaselineCompiledProcedure<T extends AbstractServerCommandSource<T>>(
        Identifier id,
        List<SourcedCommandAction<T>> entries
) implements Procedure<T> {
}
