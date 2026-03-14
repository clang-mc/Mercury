package asia.lira.mercury.mixin;

import asia.lira.mercury.ir.FunctionIrRegistry;
import net.minecraft.server.function.CommandFunctionManager;
import net.minecraft.server.function.FunctionLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandFunctionManager.class)
public class MixinCommandFunctionManager {
    @Inject(method = "load", at = @At("TAIL"))
    private void mercury$rebuildSemanticIr(FunctionLoader loader, CallbackInfo ci) {
        FunctionIrRegistry.getInstance().rebuildSemantic(loader.getFunctions());
    }
}
