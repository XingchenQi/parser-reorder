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
    public static final String PATCH_LINE_SEP = "==========================";

    private static final Map<Integer, List<String>> locateTestList = new HashMap<>();

    private InstrumentingSmartRunner runner;

    private String testname;

    // obtain all the test classes
    private static Set<String> testClasses;

    // Get all test source files
    private static List<Path> testFiles;

    // Get all java source files
    private static List<Path> javaFiles;

    // Total test order shuffle times
    private static final Integer shuffleTimes = 5;

    // useful for modules with JUnit 4 tests but depend on something in JUnit 5
    private final boolean forceJUnit4 = Configuration.config().getProperty("dt.detector.forceJUnit4", false);

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

    private void shuffleAllTests(List<String> originalTests,Integer threshold){
        List<String> newTests=new LinkedList<>(originalTests);
        List<String> bestOrder=new LinkedList<>(originalTests);
        Set<List<String>> setOfLists = new HashSet<>();
        setOfLists.add(new LinkedList<>(originalTests));
        boolean hasBetterOrder=false;
        for(int i=0;i<shuffleTimes;i++){
            // Generate a seed and print it
            long generatedSeed = new Random().nextLong();
            System.out.println("Generated seed: " + generatedSeed);

            // Use the generated seed for reproducibility
            Random random = new Random(generatedSeed);

            Collections.shuffle(newTests,random);
            if(setOfLists.contains(newTests)){
                System.out.println("CURRENT TESTS ORDER EXISTS, SKIPPING.....");
                continue;
            }
            setOfLists.add(new LinkedList<>(newTests));
            System.out.println("NEW TESTS ORDER: " + newTests);
            Map<String, TestResult> newResultsRandom = this.runner.runList(newTests).get().results();
            System.out.println("RUNNING RESULTS WITH NEW ORDER: " + newResultsRandom);
            Integer failedCnt=0;
            for (String key : newResultsRandom.keySet()) {
                TestResult testResult = newResultsRandom.get(key);
                if (testResult.result().toString().equals("FAILURE")) {
                    failedCnt++;
                }
                if (testResult.result().toString().equals("ERROR")) {
                    failedCnt++;
                }
                if (testResult.result().toString().equals("SKIPPED")) {
                    System.out.println("TESTS SKIPPED!!!");
                    continue;
                }
            }
            if(failedCnt<threshold){
                hasBetterOrder=true;
                bestOrder=new LinkedList<>(newTests);
                System.out.println("FOUND BETTER ORDER WITH MORE PASSES!");
                System.out.println("NEW TESTS ORDER: " + newTests);
                threshold=failedCnt;
            }
        }
        if(hasBetterOrder){
            System.out.println("THERE IS A BETTER ORDER THAN ORIGINAL!");
        }else{
            System.out.println("NO BETTER ORDER THAN ORIGINAL!");
        }
        System.out.println("THE BEST ORDER IN THIS CLASS IS: ");
        System.out.println(bestOrder);
    }
    
    private void checkTestsOrder(Map<String,List<String>> splitTests){
        System.out.println("=====START CHECKING TESTS ORDER AFTER SPLIT=====\n");

        splitTests.forEach((key, value) -> System.out.println(key + " " + value));
        List<String> allTests=new ArrayList<>();
        List<String> allClasses=new ArrayList<>();
        for(String newClass:splitTests.keySet()){
            allClasses.add(newClass);
            List<String> testsInOrder=splitTests.get(newClass);
            allTests.addAll(testsInOrder);
        }
        HashSet<List<String>> allOrders=new HashSet<>();
        allOrders.add(new ArrayList<>(allTests));
        System.out.println("ALL TESTS ORDER: " + allTests);
        Map<String, TestResult> newResultsInOrder = this.runner.runList(allTests).get().results();
        System.out.println("RUNNING RESULTS WITH ALL TESTS IN ABOVE ORDER: " + newResultsInOrder);
        boolean foundFail=false;
        for(int i=0;i<shuffleTimes;i++){
            // Generate a seed and print it
            long generatedSeed = new Random().nextLong();
            System.out.println("Generated seed: " + generatedSeed);

            // Use the generated seed for reproducibility
            Random random = new Random(generatedSeed);

            Collections.shuffle(allClasses,random);

            System.out.println("NEW CLASSES ORDER: " + allClasses);
            for(int j=0;j<shuffleTimes;j++){
                List<String> gatherAllTests=new ArrayList<>();
                for(String curClass:allClasses){
                    List<String> testsByClass=splitTests.get(curClass);
                    Collections.shuffle(testsByClass,random);
                    gatherAllTests.addAll(testsByClass);
                }
                if(allOrders.contains(gatherAllTests)){
                    System.out.println("CURRENT TESTS ORDER EXISTS, SKIPPING.....");
                    continue;
                }
                allOrders.add(gatherAllTests);
                System.out.println("NEW TESTS ORDER: " + gatherAllTests);
                Map<String, TestResult> newResultsRandom = this.runner.runList(gatherAllTests).get().results();
                System.out.println("RUNNING RESULTS WITH ALL TESTS SHUFFLE: " + newResultsRandom);

                for (String key : newResultsRandom.keySet()) {
                    TestResult testResult = newResultsRandom.get(key);
                    if (testResult.result().toString().equals("FAILURE")) {
                        System.out.println("FOUND FAILURE IN CURRENT ORDER! "+key);
                        foundFail=true;
                        break;
                    }
                    if (testResult.result().toString().equals("ERROR")) {
                        System.out.println("FOUND ERROR IN CURRENT ORDER! "+key);
                        foundFail=true;
                        break;
                    }
                }
                if(foundFail){
                    System.out.println("==========FOUND FAILURES/ERRORS IN CURRENT ABOVE ORDER! " +
                            "PLEASE REMAIN THE ORIGINAL ORDER!==========");
                    break;
                }
            }
            if(foundFail){
                break;
            }
        }
    }

    @Override
    public void execute() {
        superExecute();
        refactor();
    }

    protected void refactor() {
        try {
            testname = Configuration.config().getProperty("parser.testname", "");
            // the cachePath for Parser here is ".dtfixingtools".
            if (!Files.exists(ParserPathManager.cachePath())) {
                Files.createDirectories(ParserPathManager.cachePath());
            }
            // load the test runners (codes fetched from iDFlakies)
            loadTestRunners(mavenProject, testname);

            //  get the full list of tests for this maven project
            final List<String> tests = getTests(mavenProject, this.runner.framework());
            // the original order path is located at ".dtfixingtools".
            if (!Files.exists(ParserPathManager.originalOrderPath())) {
                Files.write(ParserPathManager.originalOrderPath(), tests);
            }
            // obtain all the test classes
            testClasses = getTestClasses(tests);
            // Get all test source files
            testFiles = testSources();
            // Get all java source files
            javaFiles = javaSources();

            if (testname.equals("")) {
                System.out.println("Please provide test name!");
                return;
            }

            MavenProject upperProject = mavenProject;
            File baseDir = mavenProject.getBasedir();
            if (upperProject.hasParent()) {
                while (upperProject.hasParent()) {
                    if (upperProject.getParent() == null || upperProject.getParent().getBasedir() == null) {
                        break;
                    }
                    upperProject = upperProject.getParent();
                }
            }
            File upperDir = upperProject.getBasedir();
            String moduleName = ".";
            if (baseDir.toString().equals(upperDir.toString())) {
                moduleName = ".";
            } else {
                moduleName = baseDir.toString().substring(upperDir.toString().length() + 1);
            }

            boolean exist = false;
            // read the test class file one by one
            Map<String, List<String>> splitTests = new HashMap<>();
            for (String testClass : testClasses) {
                if (!testClass.equals(testname)) {
                    continue;
                }
                for (final Path file : testFiles) {
                    if (Files.exists(file) && FilenameUtils.isExtension(
                            file.getFileName().toString(), "java")) {
                        String fileName = file.getFileName().toString();
                        String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
                        if (testClass.endsWith("." + fileShortName)) {
                            final JavaFile javaFile = JavaFile.loadFile(file, classpath(),
                                    ParserPathManager.compiledPath(file).getParent(), fileShortName, "");
                            if (!testname.equals(javaFile.getPackageName() + "." + fileShortName)) {
                                continue;
                            }
                            exist = true;
                            backup(javaFile);
                            Path path = ParserPathManager.backupPath(javaFile.path());
                            final JavaFile backupJavaFile = JavaFile.loadFile(path, classpath(),
                                    ParserPathManager.compiledPath(path).getParent(), fileShortName, "");
                            System.out.println("JAVA FILE NAME: " + javaFile.path());
                            Refactor refactor = new Refactor(mavenProject, classpath(), projectClassLoader(), this.runner);
                            refactor.updateJUnitTestFiles(javaFile);
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
                            Try<TestRunResult> testRunResultTry = this.runner.runList(testsForNewClass);
                            List<String> remainTests = new LinkedList<>(testsForNewClass);
                            Map<String, TestResult> map = testRunResultTry.get().results();
                            System.out.println(map);
                            Set<String> failedTests = new HashSet<>();
                            Utils.obtainLastTestResults(map, failedTests);
                            shuffleAllTests(testsForNewClass,failedTests.size());
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
                            splitTests.put(testClass, remainTests);
                            javaFile.writeAndReloadCompilationUnit();
                            int index = 0;
                            int numOfFailedTests = failedTests.size();
                            if (numOfFailedTests == testsForNewClass.size()) {
                                System.out.println("ALL TESTS FAIL AT THE BEGINNING!!!");
                                System.exit(0);
                            }
                            FilesToSplit filesToSplit = new FilesToSplit();
                            filesToSplit.split(splitTests);
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
                                refactor.updateJUnitTestFiles(javaFile1);
                                System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                                result = MvnCommands.runMvnInstallFromUpper(upperProject, true, upperDir,
                                        moduleName);
                                System.out.println("MVN OUTPUT: " + result);
                                Map<String, TestResult> innerMap = this.runner.runList(failedTestsList).get().results();
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
                                    innerMap = this.runner.runList(failedTestsList).get().results();
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
                                splitTests.put(testClass + "New" + index, curFailedTests);
                                javaFile1.writeAndReloadCompilationUnit();
                                index ++;
                            }
                        }
                    }
                }
            }
            if (exist) {
                System.out.println(testname + " SUCCESSFULLY SPLIT AND MAKE ALL TESTS PASS");
            }
            checkTestsOrder(splitTests);
        } catch (IOException | DependencyResolutionRequiredException exception) {
            exception.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
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

    private List<Path> javaSources() {
        final List<Path> javaFiles = new ArrayList<>();
        try (final Stream<Path> paths = Files.walk(Paths.get(mavenProject.getBuild().getSourceDirectory()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(javaFiles::add);
        } catch (IOException IOE) {
	}
        return javaFiles;
    }

    private void backup(final JavaFile javaFile) throws IOException {
        final Path path = ParserPathManager.backupPath(javaFile.path());
        Files.copy(javaFile.path(), path, StandardCopyOption.REPLACE_EXISTING);
    }


    private void restore(final JavaFile javaFile) throws IOException {
        final Path path = ParserPathManager.backupPath(javaFile.path());
        Files.copy(path, javaFile.path(), StandardCopyOption.REPLACE_EXISTING);
    }
}
