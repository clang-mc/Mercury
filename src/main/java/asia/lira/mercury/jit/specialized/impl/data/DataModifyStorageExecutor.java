package asia.lira.mercury.jit.specialized.impl.data;

import asia.lira.mercury.jit.ExecutionFrame;
import asia.lira.mercury.jit.specialized.api.SpecializedExecutor;
import net.minecraft.server.command.ServerCommandSource;

public final class DataModifyStorageExecutor implements SpecializedExecutor<DataModifyStoragePlan> {
    private static final DataModifyStorageExecutor INSTANCE = new DataModifyStorageExecutor();

    public static void executeDirect(DataModifyStoragePlan plan, ExecutionFrame frame, ServerCommandSource source) throws Exception {
        INSTANCE.execute(plan, frame, source);
    }

    @Override
    public void execute(DataModifyStoragePlan plan, ExecutionFrame frame, ServerCommandSource source) throws Exception {
        switch (plan.operation()) {
            case SET_VALUE -> StorageAccessRuntime.setValue(plan.targetStorageId(), plan.targetPath(), plan.value());
            case SET_FROM_STORAGE -> StorageAccessRuntime.setFromStorage(plan.targetStorageId(), plan.targetPath(), plan.sourceStorageId(), plan.sourcePath());
            case MERGE_VALUE -> StorageAccessRuntime.mergeValue(plan.targetStorageId(), plan.targetPath(), plan.value());
            case MERGE_FROM_STORAGE -> StorageAccessRuntime.mergeFromStorage(plan.targetStorageId(), plan.targetPath(), plan.sourceStorageId(), plan.sourcePath());
        }
    }
}
