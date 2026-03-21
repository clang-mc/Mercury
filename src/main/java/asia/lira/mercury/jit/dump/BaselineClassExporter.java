package asia.lira.mercury.jit.dump;

import asia.lira.mercury.jit.BaselineCompiledFunctionRegistry;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class BaselineClassExporter {
    private BaselineClassExporter() {
    }

    public static ExportResult exportClasses(Path runDirectory) throws IOException {
        Path outputDirectory = runDirectory.resolve("mercury").resolve("dumped").resolve("classes");
        clearDirectory(outputDirectory);
        Files.createDirectories(outputDirectory);

        int exported = 0;
        for (Identifier id : BaselineCompiledFunctionRegistry.getInstance().ids()) {
            BaselineCompiledFunctionRegistry.CompiledArtifact artifact = BaselineCompiledFunctionRegistry.getInstance().getArtifact(id);
            if (artifact == null) {
                continue;
            }

            Path path = outputDirectory.resolve(id.getNamespace()).resolve(id.getPath() + ".class");
            Files.createDirectories(path.getParent());
            Files.write(path, artifact.classBytes());
            exported++;
        }

        return new ExportResult(outputDirectory, exported);
    }

    private static void clearDirectory(Path outputDirectory) throws IOException {
        if (!Files.exists(outputDirectory)) {
            return;
        }
        try (var stream = Files.walk(outputDirectory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    public record ExportResult(
            Path outputDirectory,
            int exportedCount
    ) {
    }
}
