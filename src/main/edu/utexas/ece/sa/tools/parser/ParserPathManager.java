package edu.utexas.ece.sa.tools.parser;

import edu.utexas.ece.sa.tools.detection.MavenDetectorPathManager;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.project.MavenProject;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ParserPathManager extends MavenDetectorPathManager {
    public static final String BACKUP_EXTENSION = ".orig";
    public static final String PATCH_EXTENSION = ".patch";

    public static final Path FIXER = Paths.get("fixer");
    public static final Path FIXER_LOG = Paths.get("fixer.log");

    public static Path fixer() {
        return path(FIXER);
    }

    public static Path fixer(final Path relative) {
        return path(FIXER.resolve(relative));
    }

    public static Path fixer(final String dependentTest) {
        return fixer(Paths.get(dependentTest));
    }

    public static Path backupPath(final Path path) {
        if (path.getParent() == null) {
            return Paths.get(path.getFileName().toString() + BACKUP_EXTENSION);
        } else {
            return path.getParent().resolve(path.getFileName().toString() + BACKUP_EXTENSION);
        }
    }

    public static Path backupPath1(final Path path, final String extensions) {
        if (path.getParent() == null) {
            return Paths.get(path.getFileName().toString().replace(".java", extensions));
        } else {
            return path.getParent().resolve(path.getFileName().toString().replace(".java", extensions));
        }
    }

    // TODO: Move to a utility class (and eunomia)
    public static Path changeExtension(final Path path, final String newExtension) {
        final String extToAdd = newExtension.startsWith(".") ? newExtension : "." + newExtension;

        return path.toAbsolutePath().getParent().resolve(FilenameUtils.removeExtension(path.getFileName().toString()) + extToAdd);
    }

    public static Path compiledPath(final Path sourcePath) {
        final Path testSrcDir = Paths.get(mavenProject.getBuild().getTestSourceDirectory());
        final Path testBinDir = Paths.get(mavenProject.getBuild().getTestOutputDirectory());

        final Path relative = testSrcDir.relativize(sourcePath.toAbsolutePath());

        return testBinDir.resolve(changeExtension(relative, "class"));
    }

    public ParserPathManager(MavenProject project) {
        super(project);
    }
}
