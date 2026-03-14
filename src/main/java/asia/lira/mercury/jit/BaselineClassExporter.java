package asia.lira.mercury.jit;

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
        recreateDirectory(outputDirectory);

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

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (RuntimeException e) {
                if (e.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw e;
            }
        }
        Files.createDirectories(directory);
    }

    public record ExportResult(
            Path outputDirectory,
            int exportedCount
    ) {
    }
}
