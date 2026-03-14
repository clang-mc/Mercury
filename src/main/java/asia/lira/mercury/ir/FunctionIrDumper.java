package asia.lira.mercury.ir;

import asia.lira.mercury.jit.BaselineCompiledFunctionRegistry;
import asia.lira.mercury.jit.JitPreparationRegistry;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public final class FunctionIrDumper {
    private FunctionIrDumper() {
    }

    public static List<String> dumpRegistrySummary(FunctionIrRegistry registry, int limit) {
        List<Identifier> ids = registry.getParsedIds().stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .toList();

        List<String> lines = new ArrayList<>();
        lines.add("Mercury IR registry");
        lines.add("raw=" + registry.getRawSourceCount() + ", parsed=" + registry.getParsedCount() + ", semantic=" + registry.getSemanticCount());
        lines.add("prepared=" + JitPreparationRegistry.getInstance().preparedFunctionCount()
                + ", objectives=" + JitPreparationRegistry.getInstance().objectiveRegistry().count()
                + ", slots=" + JitPreparationRegistry.getInstance().slotRegistry().count()
                + ", baseline=" + BaselineCompiledFunctionRegistry.getInstance().count());

        int max = Math.min(limit, ids.size());
        for (int i = 0; i < max; i++) {
            FunctionIrRegistry.ParsedFunctionIr parsed = registry.getParsed(ids.get(i)).orElseThrow();
            lines.add("- " + parsed.id() + " nodes=" + parsed.nodes().size() + " macros=" + parsed.hasMacros());
        }

        if (ids.size() > max) {
            lines.add("... " + (ids.size() - max) + " more");
        }
        return lines;
    }

    public static List<String> dumpParsed(FunctionIrRegistry.ParsedFunctionIr functionIr) {
        List<String> lines = new ArrayList<>();
        lines.add("Parse IR: " + functionIr.id());
        lines.add("variables=" + functionIr.variableSlots() + ", hasMacros=" + functionIr.hasMacros());

        for (int i = 0; i < functionIr.nodes().size(); i++) {
            FunctionIrRegistry.ParseNode node = functionIr.nodes().get(i);
            if (node instanceof FunctionIrRegistry.CommandParseNode commandNode) {
                lines.add("[" + i + "] command kind=" + commandNode.controlFlowKind() + " text=" + commandNode.sourceText());
                lines.add("    path=" + commandNode.binding().rootPath() + ", commandClass=" + commandNode.binding().commandClassName());
                if (!commandNode.arguments().isEmpty()) {
                    lines.add("    arguments=" + commandNode.arguments().stream()
                            .map(argument -> argument.name() + ":" + argument.valueType() + "=" + argument.valuePreview())
                            .collect(Collectors.joining(", ")));
                }
                for (FunctionIrRegistry.ContextLayerIr layer : commandNode.contextLayers()) {
                    lines.add("    layer path=" + layer.nodePath()
                            + " range=" + layer.rangeStart() + ".." + layer.rangeEnd()
                            + " fork=" + layer.forked()
                            + " redirect=" + layer.hasRedirectModifier()
                            + " exec=" + layer.executable());
                }
                if (commandNode.targetFunctionId() != null) {
                    lines.add("    targetFunction=" + commandNode.targetFunctionId());
                }
                if (!commandNode.notes().isEmpty()) {
                    lines.add("    notes=" + commandNode.notes());
                }
                continue;
            }

            FunctionIrRegistry.MacroTemplateParseNode macroNode = (FunctionIrRegistry.MacroTemplateParseNode) node;
            lines.add("[" + i + "] macro template=" + macroNode.sourceText());
            lines.add("    variables=" + macroNode.variables() + ", slots=" + macroNode.dependentVariableSlots());
            lines.add("    segments=" + macroNode.segments());
        }

        return lines;
    }

    public static List<String> dumpSemantic(FunctionIrRegistry.SemanticFunctionIr functionIr) {
        List<String> lines = new ArrayList<>();
        lines.add("Semantic IR: " + functionIr.id());
        lines.add("tailCallRewrite=" + functionIr.hasTailCallRewrite());

        for (int i = 0; i < functionIr.nodes().size(); i++) {
            FunctionIrRegistry.SemanticNode node = functionIr.nodes().get(i);
            lines.add("[" + i + "] op=" + node.opKind() + " text=" + node.sourceText());
            if (node.targetFunctionId() != null) {
                lines.add("    targetFunction=" + node.targetFunctionId());
            }
            if (node.rewrittenJumpTarget() != null) {
                lines.add("    rewrittenJump=" + node.rewrittenJumpTarget());
            }
            if (!node.notes().isEmpty()) {
                lines.add("    notes=" + node.notes());
            }
        }

        return lines;
    }
}
