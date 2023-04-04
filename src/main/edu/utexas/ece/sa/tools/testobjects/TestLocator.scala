package edu.utexas.ece.sa.tools.testobjects

import edu.utexas.ece.sa.tools.data.framework.TestFramework
import edu.utexas.ece.sa.tools.utility.ProjectClassLoader
import org.apache.maven.plugin.surefire.util.DirectoryScanner
import edu.utexas.ece.sa.tools.utility.ProjectWrapper
import org.apache.maven.surefire.testset.TestListResolver
import org.apache.maven.project.MavenProject
import scala.collection.JavaConverters._

import java.nio.file.{Path, Paths}

object TestLocator {
    def testOutputPaths(project: ProjectWrapper): Stream[Path] = 
            project.getBuildTestOutputDirectories.asScala.toStream.map(outDir => Paths.get(outDir))

    def testClasses(project: ProjectWrapper): Stream[String] =
        testOutputPaths(project).flatMap(outPath => 
            new DirectoryScanner(outPath.toFile, TestListResolver.getWildcard)
                .scan().getClasses.asScala.toStream)

    def tests(project: ProjectWrapper, framework: TestFramework): Stream[String] =
        testClasses(project).flatMap(className =>
            GeneralTestClass
                .create(new ProjectClassLoader(project).loader, className, framework)
                .map(_.tests()).getOrElse(Stream.empty))

    // Needed for backward compatibility
    def testOutputPath(project: MavenProject): Path = Paths.get(project.getBuild.getTestOutputDirectory)

    def testClasses(project: MavenProject): Stream[String] =
        new DirectoryScanner(testOutputPath(project).toFile, TestListResolver.getWildcard)
        .scan().getClasses.asScala.toStream

    def tests(project: MavenProject, framework: TestFramework): Stream[String] =
        testClasses(project).flatMap(className =>
            GeneralTestClass
                .create(new ProjectClassLoader(project).loader, className, framework)
                .map(_.tests()).getOrElse(Stream.empty))
}
