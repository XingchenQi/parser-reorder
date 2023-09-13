package edu.utexas.ece.sa.tools.parser;

import com.github.javaparser.ast.body.*;

import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.framework.TestFramework;
import edu.illinois.cs.testrunner.data.results.TestResult;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import edu.utexas.ece.sa.tools.mavenplugin.AbstractParserMojo;

import edu.illinois.cs.testrunner.testobjects.TestLocator;
import edu.utexas.ece.sa.tools.runner.InstrumentingSmartRunner;
import edu.utexas.ece.sa.tools.utility.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.MavenInvocationException;
import scala.collection.JavaConverters;
import scala.util.Try;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

@Mojo(name = "parse", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class ParserMojo extends AbstractParserMojo {
    private static final Map<Integer, List<String>> locateTestList = new HashMap<>();

    private InstrumentingSmartRunner runner;

    private String testName;

    // obtain all the test classes
    private Set<String> testClasses;

    // Get all test source files
    private List<Path> testFiles;

    // useful for modules with JUnit 4 tests but depend on something in JUnit 5
    private final boolean forceJUnit4 = Configuration.config().getProperty("dt.detector.forceJUnit4", false);

    private Path file;

    private String fileShortName;

    private JavaFile javaFile;

    private static int index = 0;

    private String testClass;

    private JavaFile backupJavaFile;

    private MavenProject upperProject;

    private String moduleName;

    private File upperDir;

    private List<String> tests;

    private Map<String, List<String>> curTests;

    private boolean restore;

    private boolean runFromMvn;

    private String classpath() throws DependencyResolutionRequiredException {
        final List<String> elements = new ArrayList<>(mavenProject.getCompileClasspathElements());
        elements.addAll(mavenProject.getRuntimeClasspathElements());
        elements.addAll(mavenProject.getTestClasspathElements());

        return String.join(File.pathSeparator, elements);
    }

    private URLClassLoader projectClassLoader() throws DependencyResolutionRequiredException {
        // Get the project classpath, it will be useful for many things
        List<URL> urlList = new ArrayList();
        for (String cp : classpath().split(":")) {
            try {
                urlList.add(new File(cp).toURL());
            } catch (MalformedURLException mue) {
                System.out.println("Classpath element " + cp + " is malformed!");
            }
        }
        URL[] urls = urlList.toArray(new URL[urlList.size()]);
        return URLClassLoader.newInstance(urls);
    }

    private List<String> locateTests(MavenProject project, TestFramework testFramework) {
        int id = Objects.hash(project, testFramework);
        if (!locateTestList.containsKey(id)) {
            System.out.println("Locating tests...");
            try {
		            locateTestList.put(id, OperationTime.runOperation(() -> {
                    return new ArrayList<String>(JavaConverters.bufferAsJavaList(
                            TestLocator.tests(mavenProject, testFramework).toBuffer()));
                }, (tests, time) -> {
                        System.out.println("Located " + tests.size() + " " +
                                "tests. Time taken: " + time.elapsedSeconds() + " seconds");
                    return tests;
                }));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return locateTestList.get(id);
    }

    protected void loadTestRunners(final MavenProject mavenProject, String testname) throws IOException {
        // Currently there could two runners, one for JUnit 4 and one for JUnit 5
        // If the maven project has both JUnit 4 and JUnit 5 tests, two runners will
        // be returned
        List<Runner> runners = RunnerFactory.allFrom(mavenProject);
        runners = removeZombieRunners(runners, mavenProject);

        if (runners.size() != 1) {
            if (forceJUnit4) {
                Runner nrunner = null;
                for (Runner runner : runners) {
                    if (runner.framework().toString() == "JUnit") {
                        nrunner = runner;
                        break;
                    }
                }
                if (nrunner != null) {
                    runners = new ArrayList<>(Arrays.asList(nrunner));
                } else {
                    String errorMsg;
                    if (runners.size() == 0) {
                        errorMsg =
                                "Module is not using a supported test framework (probably not JUnit), " +
                                        "or there is no test.";
                    } else {
                        errorMsg = "dt.detector.forceJUnit4 is true but no JUnit 4 " +
                                "runners found. Perhaps the project only contains JUnit 5 tests.";
                    }
                    System.out.println(errorMsg);
                    return;
                }
            } else {
                String errorMsg;
                if (runners.size() == 0) {
                    errorMsg =
                            "Module is not using a supported test framework (probably not JUnit), " +
                                    "or there is no test.";
                } else {
                    // more than one runner, figure out what type the desired
                    // test is by reflecting, then use corresponding runner.
                    try {
                        String framework = "";
                        Class testClass = projectClassLoader().loadClass(testname);
                        Method[] declaredMethods = testClass.getDeclaredMethods();
                        Method[] methods = testClass.getMethods();
                        Method[] allMethods = Arrays.copyOf(declaredMethods,
                                declaredMethods.length + methods.length);
                        System.arraycopy(methods, 0, allMethods, declaredMethods.length, methods.length);
                        // check annotations on all methods to determine which framework to use
                        for (Method meth : allMethods) {
                            for (Annotation a : meth.getDeclaredAnnotations()) {
                                if (a.toString().equals("@org.junit.Test()")) {
                                    framework = "JUnit";
                                    break;
                                } else if (a.toString().equals("@org.junit.jupiter.api.Test()")) {
                                    framework = "JUnit5";
                                    break;
                                }
                            }
                            if (!framework.equals("")) {
                                break;
                            }
                        }
                        // identified the framework, so search through runners finding the corresponding one
                        for (Runner r : runners) {
                            if (r.framework().toString().equals(framework)) {
                                this.runner = InstrumentingSmartRunner.fromRunner(r, mavenProject.getBasedir());
                                return;
                            }
                        }
                    } catch (Exception ex) {
                        System.out.println("Caught exception when trying to determine best framework: " + ex);
                    }

                    errorMsg =
                            "This project contains both JUnit 4 and JUnit 5 tests, which currently"
                                    + " is not supported by iDFlakies";
                }
                System.out.println(errorMsg);
                return;
            }
        }

        if (this.runner == null) {
            this.runner = InstrumentingSmartRunner.fromRunner(runners.get(0), mavenProject.getBasedir());
        }
    }

    private List<Runner> removeZombieRunners(
            List<Runner> runners, MavenProject project) throws IOException {
        // Some projects may include test frameworks without corresponding tests.
        // Filter out such zombie test frameworks (runners).
        // For example, a project have both JUnit 4 and 5 dependencies, but there is
        // no JUnit 4 tests. In such case, we need to remove the JUnit 4 runner.
        List<Runner> aliveRunners = new ArrayList<>();
        for (Runner runner : runners) {
            if (locateTests(project, runner.framework()).size() > 0) {
                aliveRunners.add(runner);
            }
        }
        return aliveRunners;
    }

    protected List<String> getTests(
            final MavenProject mavenProject,
            TestFramework testFramework) throws IOException {
        return getOriginalOrder(mavenProject, testFramework);
    }

    public List<String> getOriginalOrder(
            final MavenProject mavenProject,
            TestFramework testFramework) throws IOException {
        return getOriginalOrder(mavenProject, testFramework, false);
    }

    public List<String> getOriginalOrder(
            final MavenProject mavenProject,
            TestFramework testFramework,
            boolean ignoreExisting) throws IOException {
        if (!Files.exists(ParserPathManager.path(Paths.get("original-order"))) || ignoreExisting) {
            System.out.println("Getting original order by parsing logs. ignoreExisting set to: " + ignoreExisting);

            try {
                final Path surefireReportsPath = Paths.get(
                        mavenProject.getBuild().getDirectory()).resolve("surefire-reports");
                final Path mvnTestLog = ParserPathManager.path(Paths.get("mvn-test.log"));
                if (Files.exists(mvnTestLog) && Files.exists(surefireReportsPath)) {
                    final List<TestClassData> testClassData = new GetMavenTestOrder(
                            surefireReportsPath, mvnTestLog).testClassDataList();

                    final List<String> tests = new ArrayList<>();

                    String delimiter = testFramework.getDelimiter();

                    for (final TestClassData classData : testClassData) {
                        for (final String testName : classData.testNames) {
                            tests.add(classData.className + delimiter + testName);
                        }
                    }
                    return tests;
                } else {
                    return locateTests(mavenProject, testFramework);
                }
            } catch (Exception ignored) {}

            return locateTests(mavenProject, testFramework);
        } else {
            return Files.readAllLines(ParserPathManager.path(Paths.get("original-order"))) ;
        }
    }

    protected void superExecute() {
        super.execute();
    }

    @Override
    public void execute() {
        superExecute();
        searching();
    }

    protected void searching() {
        try {
            testName = Configuration.config().getProperty("parser.testname", "");
            String fromMvn = Configuration.config().getProperty("parser.fromMaven", "false");
            if (fromMvn.equals("false")) {
                runFromMvn = false;
            } else if (fromMvn.equals("true")) {
                runFromMvn = true;
            }
            // the cachePath for Parser here is ".dtfixingtools".
            if (!Files.exists(ParserPathManager.cachePath())) {
                Files.createDirectories(ParserPathManager.cachePath());
            }
            // load the test runners (codes fetched from iDFlakies)
            loadTestRunners(mavenProject, testName);

            //  get the full list of tests for this maven project
            tests = getTests(mavenProject, this.runner.framework());
            // the original order path is located at ".dtfixingtools".
            if (!Files.exists(ParserPathManager.originalOrderPath())) {
                Files.write(ParserPathManager.originalOrderPath(), tests);
            }
            // obtain all the test classes
            testClasses = getTestClasses(tests);
            // Get all test source files
            testFiles = testSources();

            if (testName.equals("")) {
                System.out.println("Please provide test name!");
                return;
            }

            upperProject = mavenProject;
            File baseDir = mavenProject.getBasedir();
            if (upperProject.hasParent()) {
                while (upperProject.hasParent()) {
                    if (upperProject.getParent() == null || upperProject.getParent().getBasedir() == null) {
                        break;
                    }
                    upperProject = upperProject.getParent();
                }
            }
            upperDir = upperProject.getBasedir();
            moduleName = ".";
            if (baseDir.toString().equals(upperDir.toString())) {
                moduleName = ".";
            } else {
                moduleName = baseDir.toString().substring(upperDir.toString().length() + 1);
            }

            restore = false;

            boolean exist = false;
            for (String testClass : testClasses) {
                if (!testClass.equals(testName)) {
                    continue;
                }
                for (final Path file : testFiles) {
                    if (Files.exists(file) && FilenameUtils.isExtension(
                            file.getFileName().toString(), "java")) {
                        String fileName = file.getFileName().toString();
                        this.file = file;
                        this.testClass = testClass;
                        fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
                        if (testClass.endsWith("." + fileShortName)) {
                            javaFile = JavaFile.loadFile(file, classpath(),
                                    ParserPathManager.compiledPath(file).getParent(), fileShortName, "");
                            if (!testName.equals(javaFile.getPackageName() + "." + fileShortName)) {
                                continue;
                            }
                            exist = true;
                            parse();
                            if (runFromMvn) {
                                return;
                            }
                        }
                    }
                }
            }
            if (exist) {
                System.out.println(testName + " SUCCESSFULLY SPLIT AND MAKE ALL TESTS PASS");
                if (restore) {
                    System.out.println(testName + " HAS TESTS RESTORED TO THE ORIGINAL FILE");
                }
            }
            ShuffleOrdersUtils.checkTestsOrder(curTests, runner);
        } catch (IOException | DependencyResolutionRequiredException exception) {
            exception.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    protected void parse() {
        try {
            backup(javaFile);
            Path path = ParserPathManager.backupPath(javaFile.path());
            backupJavaFile = JavaFile.loadFile(path, classpath(),
                    ParserPathManager.compiledPath(path).getParent(), fileShortName, "");
            System.out.println("JAVA FILE NAME: " + javaFile.path());
            Refactor refactor = new Refactor(mavenProject, classpath(), projectClassLoader(), this.runner);
            refactor.updateJUnitTestFiles(javaFile);
            if (runFromMvn) {
                System.out.println("WILL RUN FROM MAVEN!!!");
                return;
            }
            System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
            boolean result = MvnCommands.runMvnInstallFromUpper(upperProject, false,
                    upperDir, moduleName);
            System.out.println("MVN OUTPUT: " + result);
            List<String> testsForNewClass = new LinkedList<>();
            for (String testForNewClass : tests) {
                String testClassForNewClass = testForNewClass.substring(0, testForNewClass.lastIndexOf(
                        this.runner.framework().getDelimiter()));
                if (testClassForNewClass.equals(testClass)) {
                    testsForNewClass.add(testForNewClass);
                }
            }
            // loadTestRunners(mavenProject, testName);
            Try<TestRunResult> testRunResultTry = this.runner.runList(testsForNewClass);
            List<String> remainTests = new LinkedList<>(testsForNewClass);
            Map<String, TestResult> map = testRunResultTry.get().results();
            System.out.println(map);
            Set<String> failedTests = new HashSet<>();
            Utils.obtainLastTestResults(map, failedTests);
            if (failedTests.size() == 0) {
                curTests = new HashMap<>();
                curTests.put(testClass, testsForNewClass);
                return;
            }
            List<String> bestOrder = ShuffleOrdersUtils.shuffleAllTests(testsForNewClass,
                    failedTests.size(), runner);
            map = this.runner.runList(bestOrder).get().results();
            failedTests = new HashSet<>();
            Utils.obtainLastTestResults(map, failedTests);
            for (String failedTest : failedTests) {
                String formalFailedTest = failedTest;
                if (failedTest.contains("#") || failedTest.contains("()")) {
                    formalFailedTest = formalFailedTest.replace("#", ".");
                    formalFailedTest = formalFailedTest.replace("()", "");
                }
                MethodDeclaration md = javaFile.findMethodDeclaration(formalFailedTest);
                javaFile.removeMethod(md);
                remainTests.remove(failedTest);
            }
            // read the test class file one by one
            curTests = new HashMap<>();
            curTests.put(testClass, remainTests);
            javaFile.writeAndReloadCompilationUnit();
            index = 0;
            int numOfFailedTests = failedTests.size();
            if (numOfFailedTests == testsForNewClass.size()) {
                System.out.println("ALL TESTS FAIL AT THE BEGINNING!!!");
                System.exit(0);
            }
            split(failedTests);
        } catch (IOException | DependencyResolutionRequiredException
                | ClassNotFoundException | MavenInvocationException ioException) {
            ioException.printStackTrace();
        }
    }

    protected void split(Set<String> failedTests) {
        try {
            int numOfFailedTests = failedTests.size();
            while (!failedTests.isEmpty()) {
                System.out.println("FILEPATH: " + file);
                // file refers to the current file path, replace the current path *.java to *New<d>.java.
                // d here refers to the digit
                Path path1 = ParserPathManager.backupPath1(file, "New" + index + ".java");
                Utils.backup1(javaFile, "New" + index + ".java");
                JavaFile javaFile1 = JavaFile.loadFile(path1, classpath(),
                        ParserPathManager.compiledPath(path1).getParent(), fileShortName,
                        "New" + index);
                for (MethodDeclaration md : javaFile1.findMethodsWithAnnotation("Test")) {
                    javaFile1.removeMethod(md);
                }
                javaFile1.writeAndReloadCompilationUnit();
                List<String> failedTestsList = new LinkedList<>();
                System.out.println("---------------FAILED TESTS TO BE SPLIT---------------");
                for (String failedTest : failedTests) {
                    String longFailedTestClassName = testClass + "New" + index;
                    String shortFailedTestName = failedTest.substring(
                            failedTest.lastIndexOf(this.runner.framework().getDelimiter()) + 1);
                    String formalShortFailedTestName = shortFailedTestName;
                    if (formalShortFailedTestName.contains("()")) {
                        formalShortFailedTestName =
                                formalShortFailedTestName.replace("()", "");
                    }
                    MethodDeclaration newMD = javaFile1.addMethod(
                            longFailedTestClassName + "." + formalShortFailedTestName);
                    failedTestsList.add(longFailedTestClassName +
                            this.runner.framework().getDelimiter() + shortFailedTestName);
                    System.out.println("failed test: " + longFailedTestClassName +
                            this.runner.framework().getDelimiter() + shortFailedTestName);
                    MethodDeclaration md = backupJavaFile.findMethodDeclaration(
                            testClass + "." + formalShortFailedTestName);
                    newMD.setThrownExceptions(md.getThrownExceptions());
                    newMD.setBody(md.getBody().get());
                    newMD.setAnnotations(md.getAnnotations());
                    javaFile1.writeAndReloadCompilationUnit();
                }
                List<String> curFailedTests = new ArrayList<>(failedTestsList);
                // reload the java file before splitting again
                JavaFile javaFile1Before =
                        JavaFile.loadFile(path1, classpath(),
                                ParserPathManager.compiledPath(path1).getParent(),
                                fileShortName, "New" + index);
                Refactor refactor = new Refactor(mavenProject, classpath(), projectClassLoader(), this.runner);
                refactor.updateJUnitTestFiles(javaFile1);
                System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                boolean result = MvnCommands.runMvnInstallFromUpper(upperProject, true, upperDir,
                        moduleName);
                System.out.println("MVN OUTPUT: " + result);
                // loadTestRunners(mavenProject, testName);
                List<String> bestOrder = ShuffleOrdersUtils.shuffleAllTests(failedTestsList,
                        failedTests.size(), runner);
                Map<String, TestResult> innerMap = this.runner.runList(bestOrder).get().results();
                System.out.println("NEW RUNNING RESULTS: " + innerMap);
                failedTests = new HashSet<>();
                Utils.obtainLastTestResults(innerMap, failedTests);
                int curNumOfFailedTests = failedTests.size();
                if (numOfFailedTests == curNumOfFailedTests) {
                    System.err.println("TESTS ALWAYS FAIL! RESTORE THE ORIGINAL FILE!");
                    javaFile1Before.writeAndReloadCompilationUnit();
                    javaFile1 = javaFile1Before;
                    javaFile1.writeAndReloadCompilationUnit();
                    System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                    result = MvnCommands.runMvnInstallFromUpper(upperProject,
                            true, upperDir, moduleName);
                    System.out.println("MVN OUTPUT: " + result);
                    restore = true;
                    // loadTestRunners(mavenProject, testName);
                    bestOrder = ShuffleOrdersUtils.shuffleAllTests(failedTestsList,
                            failedTests.size(), runner);
                    innerMap = this.runner.runList(bestOrder).get().results();
                    System.out.println("NEW RUNNING RESULTS: " + innerMap);
                    failedTests = new HashSet<>();
                    Utils.obtainLastTestResults(innerMap, failedTests);
                    curNumOfFailedTests = failedTests.size();
                    if (curNumOfFailedTests == numOfFailedTests) {
                        System.out.println("ENCOUNTER INFINITE LOOP!!!");
                        System.exit(0);
                    }
                }
                numOfFailedTests = curNumOfFailedTests;
                for (String failedTest : failedTests) {
                    String formalFailedTests = failedTest;
                    if (failedTest.contains("#") || failedTest.contains("()")) {
                        formalFailedTests = formalFailedTests.replace("#", ".");
                        formalFailedTests = formalFailedTests.replace("()", "");
                    }
                    MethodDeclaration md = javaFile1.findMethodDeclaration(formalFailedTests);
                    javaFile1.removeMethod(md);
                    curFailedTests.remove(failedTest);
                }
                curTests.put(testClass + "New" + index, curFailedTests);
                javaFile1.writeAndReloadCompilationUnit();
                index++;
            }
        } catch (IOException | MavenInvocationException |
                DependencyResolutionRequiredException | ClassNotFoundException exception) {
            exception.printStackTrace();
        }
    }

    protected Set<String> getTestClasses(List<String> tests) {
        Set<String> testClasses = new HashSet<>();
        String delimiter = this.runner.framework().getDelimiter();
        for (String test : tests) {
            String className = test.substring(0, test.lastIndexOf(delimiter));
            if (!testClasses.contains(className)) {
                testClasses.add(className);
            }
        }
        return testClasses;
    }

    private List<Path> testSources() throws IOException {
        final List<Path> testFiles = new ArrayList<>();
        try (final Stream<Path> paths = Files.walk(Paths.get(mavenProject.getBuild().getTestSourceDirectory()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(testFiles::add);
        }
        return testFiles;
    }

    /* private List<Path> javaSources() {
        final List<Path> javaFiles = new ArrayList<>();
        try (final Stream<Path> paths = Files.walk(Paths.get(mavenProject.getBuild().getSourceDirectory()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(javaFiles::add);
        } catch (IOException IOE) {
	}
        return javaFiles;
    } */

    private void backup(final JavaFile javaFile) throws IOException {
        final Path path = ParserPathManager.backupPath(javaFile.path());
        Files.copy(javaFile.path(), path, StandardCopyOption.REPLACE_EXISTING);
    }


    private void restore(final JavaFile javaFile) throws IOException {
        final Path path = ParserPathManager.backupPath(javaFile.path());
        Files.copy(path, javaFile.path(), StandardCopyOption.REPLACE_EXISTING);
    }
}
