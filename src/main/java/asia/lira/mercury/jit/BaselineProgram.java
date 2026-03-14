package asia.lira.mercury.jit;

import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record BaselineProgram(
        Identifier id,
        List<BaselineInstruction> instructions,
        List<Identifier> dependencies
) {
    public static final class Builder {
        private final Identifier id;
        private final List<BaselineInstruction> instructions = new ArrayList<>();
        private final Set<Identifier> dependencies = new LinkedHashSet<>();

        public Builder(Identifier id) {
            this.id = id;
        }

        public void addInstruction(BaselineInstruction instruction) {
            instructions.add(instruction);
        }

        public void addDependency(Identifier dependency) {
            dependencies.add(dependency);
        }

        public List<Identifier> dependencies() {
            return List.copyOf(dependencies);
        }

        public BaselineProgram build() {
            List<BaselineInstruction> lowered = new ArrayList<>(instructions.size());
            for (int i = 0; i < instructions.size(); i++) {
                BaselineInstruction instruction = instructions.get(i);
                if (i == instructions.size() - 1 && instruction.opCode() == BaselineInstruction.OpCode.CALL) {
                    lowered.add(BaselineInstruction.jump(instruction.targetFunction(), instruction.sourceText()));
                    continue;
                }
                lowered.add(instruction);
            }
            return new BaselineProgram(id, List.copyOf(lowered), List.copyOf(dependencies));
        }
    }
}
