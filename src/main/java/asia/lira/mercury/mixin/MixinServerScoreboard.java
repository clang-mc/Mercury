package asia.lira.mercury.mixin;

import asia.lira.mercury.jit.SynchronizationRuntime;
import net.minecraft.scoreboard.ScoreHolder;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.ScoreboardScore;
import net.minecraft.scoreboard.ServerScoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerScoreboard.class)
public class MixinServerScoreboard {
    @Inject(method = "updateScore", at = @At("TAIL"))
    private void mercury$notifyScoreUpdated(ScoreHolder scoreHolder, ScoreboardObjective objective, ScoreboardScore score, CallbackInfo ci) {
        SynchronizationRuntime.getInstance().onScoreUpdated(scoreHolder, objective, score.getScore());
    }

    @Inject(method = "resetScore", at = @At("TAIL"))
    private void mercury$notifyScoreReset(ScoreHolder scoreHolder, ScoreboardObjective objective, CallbackInfo ci) {
        SynchronizationRuntime.getInstance().onScoreRemoved(scoreHolder, objective);
    }

    @Inject(method = "onScoreHolderRemoved", at = @At("TAIL"))
    private void mercury$notifyScoreHolderRemoved(ScoreHolder scoreHolder, CallbackInfo ci) {
        SynchronizationRuntime.getInstance().onScoreHolderRemoved(scoreHolder);
    }

    @Inject(method = "onScoreRemoved", at = @At("TAIL"))
    private void mercury$notifyScoreRemoved(ScoreHolder scoreHolder, ScoreboardObjective objective, CallbackInfo ci) {
        SynchronizationRuntime.getInstance().onScoreRemoved(scoreHolder, objective);
    }

    @Inject(method = "updateRemovedObjective", at = @At("TAIL"))
    private void mercury$notifyObjectiveRemoved(ScoreboardObjective objective, CallbackInfo ci) {
        SynchronizationRuntime.getInstance().onObjectiveRemoved(objective);
    }
}
