package edu.utexas.ece.sa.tools.utility;

import com.google.common.base.Preconditions;
import edu.utexas.ece.sa.tools.configuration.Configuration;
import edu.utexas.ece.sa.tools.core.TestPluginUtil;
import edu.utexas.ece.sa.tools.utility.ProjectWrapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RunnerPathManager {
    private static final String outputPath = Configuration.config().getProperty("dt.cache.absolute.path", "");

    public RunnerPathManager() {
    }

    public static Path modulePath() {
        return TestPluginUtil.project.getBasedir().toPath();
    }

    private static ProjectWrapper getMavenProjectParent(ProjectWrapper project) {
        ProjectWrapper parentProj;
        for(parentProj = project; parentProj.getParent() != null && parentProj.getParent().getBasedir() != null; parentProj = parentProj.getParent()) {
        }

        return parentProj;
    }

    public static Path parentPath() {
        return getMavenProjectParent(TestPluginUtil.project).getBasedir().toPath();
    }

    public static Path parentPath(Path relative) {
        Preconditions.checkState(!relative.isAbsolute(), "PathManager.parentPath(): Cache paths must be relative, not absolute (%s)", relative);
        return parentPath().resolve(relative);
    }

    public static Path cachePath() {
        TestPluginUtil.project.info("Accessing cachePath: " + outputPath);
        if (outputPath == "") {
            return modulePath().resolve(".dtfixingtools");
        } else {
            Path outputPathObj = Paths.get(outputPath);

            try {
                Files.createDirectories(outputPathObj);
            } catch (IOException var2) {
                TestPluginUtil.project.debug(var2.getMessage());
            }

            return outputPathObj.resolve(modulePath().getFileName());
        }
    }

    public static Path path(Path relative) {
        Preconditions.checkState(!relative.isAbsolute(), "PathManager.path(): Cache paths must be relative, not absolute (%s)", relative);
        return cachePath().resolve(relative);
    }
}

