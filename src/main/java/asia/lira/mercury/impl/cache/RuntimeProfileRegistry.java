package asia.lira.mercury.impl.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeProfileRegistry {
    private static final RuntimeProfileRegistry INSTANCE = new RuntimeProfileRegistry();

    private final Map<Integer, MacroCallsiteProfile> macroProfiles = new LinkedHashMap<>();
    private final Map<net.minecraft.util.Identifier, FunctionExecutionProfile> functionProfiles = new LinkedHashMap<>();

    private RuntimeProfileRegistry() {
    }

    public static RuntimeProfileRegistry getInstance() {
        return INSTANCE;
    }

    public void clear() {
        macroProfiles.clear();
        functionProfiles.clear();
    }

    public void retireTier2Caller(net.minecraft.util.Identifier functionId) {
        functionProfiles.remove(functionId);
    }

    public MacroCallsiteProfile profileFor(int planId, MacroCallsiteKey key, java.util.List<String> argumentNames) {
        return macroProfiles.computeIfAbsent(planId, ignored -> new MacroCallsiteProfile(key, argumentNames));
    }

    public Map<Integer, MacroCallsiteProfile> macroProfiles() {
        return Map.copyOf(macroProfiles);
    }

    public int incrementFunctionExecution(net.minecraft.util.Identifier functionId) {
        return functionProfiles.computeIfAbsent(functionId, FunctionExecutionProfile::new).incrementAndGet();
    }

    public int functionExecutions(net.minecraft.util.Identifier functionId) {
        FunctionExecutionProfile profile = functionProfiles.get(functionId);
        return profile == null ? 0 : profile.executions();
    }

    public Map<net.minecraft.util.Identifier, FunctionExecutionProfile> functionProfiles() {
        return Map.copyOf(functionProfiles);
    }
}
