package asia.lira.mercury.jit.pipeline;

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
            return new BaselineProgram(id, List.copyOf(instructions), List.copyOf(dependencies));
        }
    }
}
