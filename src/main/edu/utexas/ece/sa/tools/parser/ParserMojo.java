package edu.utexas.ece.sa.tools.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.illinois.cs.testrunner.data.framework.TestFramework;
import edu.illinois.cs.testrunner.data.results.TestResult;
import edu.illinois.cs.testrunner.data.results.TestRunResult;
import edu.illinois.cs.testrunner.runner.Runner;
import edu.illinois.cs.testrunner.runner.RunnerFactory;
import edu.utexas.ece.sa.tools.mavenplugin.AbstractParserMojo;

import edu.illinois.cs.testrunner.testobjects.TestLocator;
// import edu.utexas.ece.sa.tools.testobjects.TestLocator;
import edu.utexas.ece.sa.tools.runner.InstrumentingSmartRunner;
import edu.utexas.ece.sa.tools.utility.GetMavenTestOrder;
import edu.utexas.ece.sa.tools.utility.MvnCommands;
import edu.utexas.ece.sa.tools.utility.OperationTime;
import edu.utexas.ece.sa.tools.utility.TestClassData;
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
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Stream;

@Mojo(name = "parse", defaultPhase = LifecyclePhase.TEST, requiresDependencyResolution = ResolutionScope.TEST)
public class ParserMojo extends AbstractParserMojo {
    public static final String PATCH_LINE_SEP = "==========================";

    private static Map<Integer, List<String>> locateTestList = new HashMap<>();
    private InstrumentingSmartRunner runner;

    private String testname;

    // obtain all the test classes
    private static Set<String> testClasses;
    // Get all test source files
    private static List<Path> testFiles;
    // Get all test source files for upper module
    private static List<Path> wholeTestFiles;
    // Get all java source files
    private static List<Path> javaFiles;

    //Total test order shuffle times
    private static Integer shuffleTimes=5;

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
                    return new ArrayList<String>(JavaConverters.bufferAsJavaList(TestLocator.tests(mavenProject, testFramework).toBuffer()));
                }, (tests, time) -> {
                        System.out.println("Located " + tests.size() + " tests. Time taken: " + time.elapsedSeconds() + " seconds");
                    return tests;
                }));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return locateTestList.get(id);
    }

    protected void loadTestRunners(final MavenProject mavenProject) throws IOException {
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
                        errorMsg = "dt.detector.forceJUnit4 is true but no JUnit 4 runners found. Perhaps the project only contains JUnit 5 tests.";
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
                    // more than one runner, currently is not supported.
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
                final Path surefireReportsPath = Paths.get(mavenProject.getBuild().getDirectory()).resolve("surefire-reports");
                final Path mvnTestLog = ParserPathManager.path(Paths.get("mvn-test.log"));
                if (Files.exists(mvnTestLog) && Files.exists(surefireReportsPath)) {
                    final List<TestClassData> testClassData = new GetMavenTestOrder(surefireReportsPath, mvnTestLog).testClassDataList();

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
    
    private void checkTestsOrder(HashMap<String,List<String>> splitTests){
        System.console().printf("=====START CHECKING TESTS ORDER AFTER SPLIT=====\n");

        splitTests.forEach((key, value) -> System.out.println(key + " " + value));
        List<String> allTests=new ArrayList<>();
        List<String> allClasses=new ArrayList<>();
        for(String newClass:splitTests.keySet()){
            allClasses.add(newClass);
            List<String> testsInOrder=splitTests.get(newClass);
            allTests.addAll(testsInOrder);
//            System.out.println(testsInOrder);
//            Map<String, TestResult> innerMap = this.runner.runList(testsInOrder).get().results();
//            System.out.println("RUNNING RESULTS WITH SAME ORDER: " + innerMap);
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
                    System.out.println("==========FOUND FAILURES/ERRORS IN CURRENT ABOVE ORDER! PLEASE REMAIN THE ORIGINAL ORDER!==========");
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

        try {
            testname = Configuration.config().getProperty("parser.testname", "");
            if (!Files.exists(ParserPathManager.cachePath())) {
                Files.createDirectories(ParserPathManager.cachePath());
            }
            loadTestRunners(mavenProject);
            /* final Option<Runner> runnerOption = RunnerFactory.from(mavenProject);
            if (runnerOption.isDefined()) {
                this.runner = runnerOption.get();
            } */
            final List<String> tests = getTests(mavenProject, this.runner.framework());
            if (!Files.exists(ParserPathManager.originalOrderPath())) {
                Files.write(ParserPathManager.originalOrderPath(), tests);
            }
            // obtain all the test classes
            testClasses = getTestClasses(tests);
            // Get all test source files
            testFiles = testSources();
            // Get all test source files for upper module
            wholeTestFiles = wholeTestSources();
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
            // String moduleName = baseDir.toString().substring(upperDir.toString().length() + 1);
            String moduleName = ".";
            if (baseDir.toString().equals(upperDir.toString())) {
                moduleName = ".";
            } else {
                moduleName = baseDir.toString().substring(upperDir.toString().length() + 1);
            }
            boolean exist = false;
            // read the test class file one by one
            HashMap<String,List<String>> splitTests=new HashMap<>();
            for (String testClass : testClasses) {
		        if (!testClass.equals(testname)) {
                    continue;
                }
                for (final Path file : testFiles) {
                    if (Files.exists(file) && FilenameUtils.isExtension(file.getFileName().toString(), "java")) {
                        Set<FieldDeclaration> globalFields = new HashSet<>();
                        String fileName = file.getFileName().toString();
                        String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
                        if (testClass.endsWith("." + fileShortName)) {
                            final JavaFile javaFile = JavaFile.loadFile(file, classpath(), ParserPathManager.compiledPath(file).getParent(), fileShortName, "");
                            if (!testname.equals(javaFile.getPackageName() + "." + fileShortName)) {
                                continue;
                            }
                            exist = true;
                            backup(javaFile);
                            Path path = ParserPathManager.backupPath(javaFile.path());
                            final JavaFile backupJavaFile = JavaFile.loadFile(path, classpath(),
                                    ParserPathManager.compiledPath(path).getParent(), fileShortName, "");
                            System.out.println("JAVA FILE NAME: " + javaFile.path());
                            Map<Integer, Set<String>> upperLevelClassNames = new HashMap<>();
                            final Map<Integer, Set<JavaFile>> upperLevelFiles = new HashMap<>();
                            int level = 0;
                            // put the first level test classes
                            Set<String> currentLevelClasses = getUpperLevelClasses(javaFile);
                            upperLevelClassNames.put(level, currentLevelClasses);

                            while (!currentLevelClasses.isEmpty()) {
                                for (String currentLevelClassName : currentLevelClasses) {
                                    for (final Path anotherFile : wholeTestFiles) {
                                        if (Files.exists(anotherFile) && FilenameUtils.isExtension(anotherFile.getFileName().toString(), "java")) {
                                            if (anotherFile.getFileName().toString().equals(currentLevelClassName + ".java")) {
                                                String newFileName = anotherFile.getFileName().toString();
                                                String newFileShortName = newFileName.substring(0, newFileName.lastIndexOf("."));
                                                JavaFile newJavaFile = JavaFile.loadFile(anotherFile, classpath(), ParserPathManager.compiledPath(anotherFile).getParent(), newFileShortName, "");
                                                Set<JavaFile> curLevelFiles = new HashSet<>();
                                                if (upperLevelFiles.containsKey(level)) {
                                                    curLevelFiles = upperLevelFiles.get(level);
                                                    curLevelFiles.add(newJavaFile);
                                                } else {
                                                    curLevelFiles.add(newJavaFile);
                                                    upperLevelFiles.put(level, curLevelFiles);
                                                }
                                                Set<String> newCurLvlClasses = new HashSet<>();
                                                Set<String> newUpperClasses = getUpperLevelClasses(newJavaFile);
                                                if (!newUpperClasses.isEmpty()) {
                                                    if (!upperLevelClassNames.containsKey(level + 1)) {
                                                        newCurLvlClasses.addAll(newUpperClasses);
                                                        upperLevelClassNames.put(level + 1, newCurLvlClasses);
                                                    } else {
                                                        newCurLvlClasses = upperLevelClassNames.get(level + 1);
                                                        newCurLvlClasses.addAll(newUpperClasses);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                level += 1;
                                currentLevelClasses = upperLevelClassNames.getOrDefault(level, new HashSet<>());
                            }
                            updateJUnitTestFiles(javaFile);
                            // System.exit(0);
			                boolean result = false;
                            System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                            result = MvnCommands.runMvnInstallFromUpper(upperProject, false, upperDir, moduleName);
                            System.out.println("MVN OUTPUT: " + result);
                            List<String> testsForNewClass = new LinkedList<>();
                            for (String testForNewClass : tests) {
                                String testClassForNewClass = testForNewClass.substring(0, testForNewClass.lastIndexOf(this.runner.framework().getDelimiter()));
                                if (testClassForNewClass.equals(testClass)) {
                                    testsForNewClass.add(testForNewClass);
                                }
                            }
                            Try<TestRunResult> testRunResultTry = this.runner.runList(testsForNewClass);
                            List<String> remainTests=testsForNewClass;
                            Map<String, TestResult> map = testRunResultTry.get().results();
                            System.out.println(map);
                            Set<String> failedTests = new HashSet<>();
                            obtainLastTestResults(map, failedTests);
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
//                            System.console().printf("SPLIT TESTS ");
//                            System.console().printf(testClass+" ");
//                            for(String t:remainTests){
//                                System.console().printf(t);
//                            }
                            splitTests.put(testClass,remainTests);
                            javaFile.writeAndReloadCompilationUnit();
                            int index = 0;
                            int numOfFailedTests = failedTests.size();
                            if (numOfFailedTests == testsForNewClass.size()) {
                                System.out.println("ALL TESTS FAIL AT THE BEGINNING!!!");
                                System.exit(0);
                            }
                            while (!failedTests.isEmpty()) {
                                System.out.println("FILEPATH: " + file);
                                // file refers to the current file path, replace the current path *.java to *New<d>.java.
                                // d here refers to the digit
                                Path path1 = ParserPathManager.backupPath1(file, "New" + index + ".java");
                                backup1(javaFile, "New" + index + ".java");
                                JavaFile javaFile1 = JavaFile.loadFile(path1, classpath(), ParserPathManager.compiledPath(path1).getParent(), fileShortName, "New" + index);
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
                                List<String> curFailedTests=new ArrayList<>(failedTestsList);
                                result = false;
                                // reload the java file before splitting again
                                JavaFile javaFile1Before =
                                        JavaFile.loadFile(path1, classpath(),
                                                ParserPathManager.compiledPath(path1).getParent(),
                                                fileShortName, "New" + index);
                                updateJUnitTestFiles(javaFile1);
                                System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                                result = MvnCommands.runMvnInstallFromUpper(upperProject, true, upperDir, moduleName);
                                System.out.println("MVN OUTPUT: " + result);
                                Map<String, TestResult> innerMap = this.runner.runList(failedTestsList).get().results();
                                System.out.println("NEW RUNNING RESULTS: " + innerMap);
                                failedTests = new HashSet<>();
                                obtainLastTestResults(innerMap, failedTests);
                                int curNumOfFailedTests = failedTests.size();
                                if (numOfFailedTests == curNumOfFailedTests) {
                                    System.err.println("TESTS ALWAYS FAIL! RESTORE THE ORIGINAL FILE!");
                                    javaFile1Before.writeAndReloadCompilationUnit();
                                    javaFile1 = javaFile1Before;
                                    javaFile1.writeAndReloadCompilationUnit();
                                    System.out.println("MVN INSTALL FROM THE UPPER LEVEL!");
                                    result = MvnCommands.runMvnInstallFromUpper(upperProject, true, upperDir, moduleName);
                                    System.out.println("MVN OUTPUT: " + result);
                                    innerMap = this.runner.runList(failedTestsList).get().results();
                                    System.out.println("NEW RUNNING RESULTS: " + innerMap);
                                    failedTests = new HashSet<>();
                                    obtainLastTestResults(innerMap, failedTests);
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
                                splitTests.put(testClass + "New" + index,curFailedTests);
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

    protected void updateJUnitTestFiles(JavaFile javaFile) throws DependencyResolutionRequiredException, ClassNotFoundException, IOException {
        // JUnit 4
        if (this.runner.framework().getDelimiter().equals(".")) {
            Set<String> methsSet = new HashSet<>();
            Set<String> fldsSet = new HashSet<>();
            updateJUnit4TestFiles(javaFile, true, methsSet, fldsSet);
        }
        // JUnit 5
        if (this.runner.framework().getDelimiter().equals("#")) {
            Set<String> methsSet = new HashSet<>();
            Set<String> fldsSet = new HashSet<>();
            updateJUnit5TestFiles(javaFile, true, methsSet, fldsSet);
        }
        javaFile.writeAndReloadCompilationUnit();
    }



    protected void addClassAnnotations(JavaFile javaFile, Set<String> fieldsSet, Set<String> methodsSet, String beforeAnnotation, String afterAnnotation) throws DependencyResolutionRequiredException, ClassNotFoundException {
        // method
        List<MethodDeclaration> methods = javaFile.findMethodWithAnnotations(beforeAnnotation);
        for (MethodDeclaration method : methods) {
            if (method != null) {
                method.setStatic(true);
                NodeList<AnnotationExpr> methodAnnotations = method.getAnnotations();
                NodeList<AnnotationExpr> newAnnotations = new NodeList<>();
                int i = 0;
                for (i = 0; i < methodAnnotations.size(); i++) {
                    AnnotationExpr beforeMethodAnnotation = methodAnnotations.get(i);
                    if (beforeMethodAnnotation.getName().toString().equals(beforeAnnotation)) {
                        Class clazz = projectClassLoader().loadClass(afterAnnotation);
                        method.tryAddImportToParentCompilationUnit(clazz);
                        MarkerAnnotationExpr markerAnnotationExpr = new MarkerAnnotationExpr(JavaParser.parseName(clazz.getSimpleName()));
                        newAnnotations.add((AnnotationExpr) markerAnnotationExpr);
                    } else if (beforeMethodAnnotation.getName().toString().equals("Override")) {
                        continue;
                    } else {
                        newAnnotations.add(methodAnnotations.get(i));
                    }
                }
                method.setAnnotations(newAnnotations);
                fieldsSet.addAll(getRelatedFields(method, javaFile, false));
                methodsSet.addAll(getRelatedMethods(method));
		changeMethods(method, javaFile);
            }
        }
    }

    protected void updateJUnit4TestFiles(JavaFile javaFile, boolean lowLevel, Set<String> methodsSet, Set<String> fieldsSet) throws DependencyResolutionRequiredException, ClassNotFoundException, IOException {
        // Before method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "Before", "org.junit.BeforeClass");
        // After Method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "After", "org.junit.AfterClass");

        if (!lowLevel) return;
        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            JavaFile parentFile = curFile.getExtendedJavaFile();

            // previous file information
            JavaFile backupFile = curFile;

            // obtain the extend relationship
            getUpperLevelClasses(parentFile);

            String backupName = backupFile.path().getFileName().toString();
            String backupShortName = backupName.substring(0, backupName.lastIndexOf("."));

            int i = 0;
            if (backupFile.hasExtendedJavaFile()) {
                String futureName = backupFile.getExtendedJavaFile().path().getFileName().toString();
                String futureShortName = futureName.substring(0, futureName.lastIndexOf("."));

                for (ClassOrInterfaceType ct : backupFile.getCurCI().getExtendedTypes()) {
                    if (ct.getName().asString().equals(futureShortName)) {
                        String potentialName = futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.getCurCI().getExtendedTypes(i).setName(potentialName);
                        List<ClassOrInterfaceDeclaration> classList = backupFile.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
                        for (ClassOrInterfaceDeclaration clazz : classList) {
                            if (clazz.getNameAsString().equals(backupShortName)) {
                                clazz.getExtendedTypes(i).setName(potentialName);
                            }
                        }
                        String clazzName = backupFile.getExtendedJavaFile().compilationUnit().getPackageDeclaration().get().getName().toString() + "." + futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.compilationUnit().addImport(clazzName);
                        backupFile.writeAndReloadCompilationUnit();
                    }
                    i++;
                }
            }
            int curIndex = parentFile.getCurIndex();
            Path path1 = ParserPathManager.backupPath1(parentFile.path(), "New" + curIndex + ".java");
            String fileName = parentFile.path().getFileName().toString();
            String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
            backup1(parentFile, "New" + curIndex + ".java");

            curFile = JavaFile.loadFile(path1, classpath(), ParserPathManager.compiledPath(path1).getParent(), fileShortName, "New" + curIndex);
            parentFile.setCurIndex(curIndex + 1);
            backupFile.setExtendedJavaFile(curFile);
            curFile.setExtendedJavaFile(parentFile.getExtendedJavaFile());
            curFile.writeAndReloadCompilationUnit();
            updateJUnit4TestFiles(curFile, false, methodsSet, fieldsSet);
            javaFileList.add(curFile);
        }
        updateMethods(javaFile, methodsSet, fieldsSet);
    }

    protected void updateJUnit5TestFiles(JavaFile javaFile, boolean lowLevel, Set<String> methodsSet, Set<String> fieldsSet) throws DependencyResolutionRequiredException, ClassNotFoundException, IOException {
        // BeforeEach method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "BeforeEach", "org.junit.jupiter.api.BeforeAll");
        // AfterEach Method
        addClassAnnotations(javaFile, fieldsSet, methodsSet, "AfterEach", "org.junit.jupiter.api.AfterAll");

        if (!lowLevel) return;
        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            JavaFile parentFile = curFile.getExtendedJavaFile();

            // previous file information
            JavaFile backupFile = curFile;

            // obtain the extend relationship
            getUpperLevelClasses(parentFile);

            String backupName = backupFile.path().getFileName().toString();
            String backupShortName = backupName.substring(0, backupName.lastIndexOf("."));

            int i = 0;
            if (backupFile.hasExtendedJavaFile()) {
                String futureName = backupFile.getExtendedJavaFile().path().getFileName().toString();
                String futureShortName = futureName.substring(0, futureName.lastIndexOf("."));

                for (ClassOrInterfaceType ct : backupFile.getCurCI().getExtendedTypes()) {
                    if (ct.getName().asString().equals(futureShortName)) {
                        String potentialName = futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.getCurCI().getExtendedTypes(i).setName(potentialName);
                        List<ClassOrInterfaceDeclaration> classList = backupFile.compilationUnit().findAll(ClassOrInterfaceDeclaration.class);
                        for (ClassOrInterfaceDeclaration clazz : classList) {
                            if (clazz.getNameAsString().equals(backupShortName)) {
                                clazz.getExtendedTypes(i).setName(potentialName);
                            }
                        }
                        String clazzName = backupFile.getExtendedJavaFile().compilationUnit().getPackageDeclaration().get().getName().toString() + "." + futureShortName + "New" + curFile.getExtendedJavaFile().getCurIndex();
                        backupFile.compilationUnit().addImport(clazzName);
                        backupFile.writeAndReloadCompilationUnit();
                    }
                    i++;
                }
            }

            int curIndex = parentFile.getCurIndex();
            Path path1 = ParserPathManager.backupPath1(parentFile.path(), "New" + curIndex + ".java");
            String fileName = parentFile.path().getFileName().toString();
            String fileShortName = fileName.substring(0, fileName.lastIndexOf("."));
            backup1(parentFile, "New" + curIndex + ".java");

            curFile = JavaFile.loadFile(path1, classpath(), ParserPathManager.compiledPath(path1).getParent(), fileShortName, "New" + curIndex);
            parentFile.setCurIndex(curIndex + 1);
            backupFile.setExtendedJavaFile(curFile);
            curFile.setExtendedJavaFile(parentFile.getExtendedJavaFile());
            curFile.writeAndReloadCompilationUnit();
            updateJUnit5TestFiles(curFile, false, methodsSet, fieldsSet);

            javaFileList.add(curFile);
        }
        updateMethods(javaFile, methodsSet, fieldsSet);
    }

    protected void updateMethods(JavaFile javaFile, Set<String> methodsSet, Set<String> fieldsSet) throws IOException, DependencyResolutionRequiredException, ClassNotFoundException {
        Set<String> remainingMethodsSet = new CopyOnWriteArraySet<>();
        remainingMethodsSet.addAll(methodsSet);

        List<JavaFile> javaFileList = new LinkedList<>();
        JavaFile curFile = javaFile;
        javaFileList.add(curFile);
        while (curFile.hasExtendedJavaFile()) {
            getUpperLevelClasses(curFile);
            JavaFile parentFile = curFile.getExtendedJavaFile();
            curFile = parentFile;
            javaFileList.add(curFile);
        }
        Set<String> processedMethods = new HashSet<>();
        while (true) {
            Set<String> preSet = new HashSet<>(remainingMethodsSet);
            for (String methodName : remainingMethodsSet) {
                if (processedMethods.contains(methodName)) {
                    continue;
                }
                for (JavaFile javaFile1 : javaFileList) {
                    String packageName = javaFile1.compilationUnit().getPackageDeclaration().get().getNameAsString();
                    String className = javaFile1.getCurCI().getNameAsString();
                    List<MethodDeclaration> methodsList = javaFile1.findMethodDeclarations(packageName + "." + className + "." + methodName);
                    if (methodsList.size() > 0) {
                        Set<String> additionalMethods = new HashSet<>();
                        for (MethodDeclaration md : methodsList) {
                            md.setStatic(true);
                            // deal with annotations
                            NodeList<AnnotationExpr> annotations = md.getAnnotations();
                            NodeList<AnnotationExpr> newAnnotations = new NodeList<>();
                            for (AnnotationExpr ae : annotations) {
                                if (ae.getName().toString().equals("Override")) {
                                    continue;
                                } else {
                                    newAnnotations.add(ae);
                                }
                            }
                            md.setAnnotations(newAnnotations);
                            additionalMethods.addAll(getRelatedMethods(md));
                            fieldsSet.addAll(getRelatedFields(md, javaFile1, false));
                            changeMethods(md, javaFile1);
			}
                        remainingMethodsSet.addAll(additionalMethods);
                        remainingMethodsSet.remove(methodName);
                        processedMethods.add(methodName);
                    }
                }
            }
            Set<String> curSet = new HashSet<>(remainingMethodsSet);
            if (preSet.size() == curSet.size() && preSet.containsAll(curSet)) {
                break;
            }
        }
        Set<String> newFieldsSet = new HashSet<>();
        for (String fieldName : fieldsSet) {
            for (JavaFile javaFile1 : javaFileList) {
                FieldDeclaration field = javaFile1.findFieldDeclaration(fieldName);
                if (field != null) {
                    newFieldsSet.addAll(getRelatedFields(field, javaFile1));
                }
            }
        }
        fieldsSet.addAll(newFieldsSet);
        for (String fieldName : fieldsSet) {
            for (JavaFile javaFile1 : javaFileList) {
                FieldDeclaration field = javaFile1.findFieldDeclaration(fieldName);
		        if (field != null) {
                    field.setStatic(true);
                    // rule field
                    for (AnnotationExpr annotationExpr : field.getAnnotations()) {
                        if (annotationExpr.getName().toString().equals("Rule")) {
                            NodeList<AnnotationExpr> ruleFieldAnnotations = field.getAnnotations();
                            int i = 0;
                            for (i = 0; i < ruleFieldAnnotations.size(); i++) {
                                AnnotationExpr ruleFieldAnnotation = ruleFieldAnnotations.get(i);
                                if (ruleFieldAnnotation.getName().toString().equals("Rule")) {
                                    Class clazz = projectClassLoader().loadClass("org.junit.ClassRule");
                                    field.tryAddImportToParentCompilationUnit(clazz);
                                    MarkerAnnotationExpr markerAnnotationExpr = new MarkerAnnotationExpr(JavaParser.parseName(clazz.getSimpleName()));
                                    field.setAnnotation(i, (AnnotationExpr) markerAnnotationExpr);
                                    break;
                                }
                            }
                            changeFields(field, javaFile);
                        }
                    }
                    changeFields(field, javaFile1);
                }
            }
        }
        for (JavaFile javaFile1 : javaFileList) {
            javaFile1.writeAndReloadCompilationUnit();
        }
    }

    protected Set<String> getUpperLevelClasses(JavaFile javaFile) throws DependencyResolutionRequiredException, IOException {
        ClassOrInterfaceDeclaration ci = javaFile.getCurCI();
        String currentLevelClassName = ci.getNameAsString();
        Set<String> set = new HashSet<String>();
        for (ClassOrInterfaceType type : ci.getExtendedTypes()) {
            set.add(type.getNameAsString());
            for (final Path anotherFile : wholeTestFiles) {
		        if (Files.exists(anotherFile) && FilenameUtils.isExtension(anotherFile.getFileName().toString(), "java")) {
                    if (anotherFile.getFileName().toString().equals(type.getNameAsString() + ".java")) {
                        String newFileName = anotherFile.getFileName().toString();
                        String newFileShortName = newFileName.substring(0, newFileName.lastIndexOf("."));
                        JavaFile newJavaFile = JavaFile.loadFile(anotherFile, classpath(), ParserPathManager.compiledPath(anotherFile).getParent(), newFileShortName, "");
                        javaFile.setExtendedJavaFile(newJavaFile);
			            break;
                    }
                }
            }
        }
        return set;
    }

    private void obtainLastTestResults(Map<String, TestResult> map, Set<String> failedTests) {
        for (String key : map.keySet()) {
            TestResult testResult = map.get(key);
            if (testResult.result().toString().equals("FAILURE")) {
                failedTests.add(key);
            }
            if (testResult.result().toString().equals("ERROR")) {
                failedTests.add(key);
            }
            if (testResult.result().toString().equals("SKIPPED")) {
                System.out.println("TESTS SKIPPED!!!");
                System.exit(0);
            }
        }
    }

    protected Set<String> getRelatedFields(MethodDeclaration md, JavaFile javaFile, boolean flag) {
        Map<String, Range> variableNameMap = new HashMap<>();
        Set<String> set = new HashSet<>();
        Map<VariableDeclarator, Range> localMap = new HashMap<>();
        List<Node> nodesList = new LinkedList<>();
        try {
            getUpperLevelClasses(javaFile);
        } catch (IOException | DependencyResolutionRequiredException ex) {
            ex.printStackTrace();
        }
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node instanceof VariableDeclarationExpr) {
                    NodeList<VariableDeclarator> variableDeclarators = ((VariableDeclarationExpr) node).asVariableDeclarationExpr().getVariables();
                    for (VariableDeclarator variableDeclarator: variableDeclarators) {
                        Node parentNodeForVD = variableDeclarator.getParentNode().get();
                        while (true) {
                            if (parentNodeForVD.getClass().getName().equals("com.github.javaparser.ast.stmt.BlockStmt")) {
                                localMap.put(variableDeclarator, parentNodeForVD.getRange().get());
                                break;
                            }
                            parentNodeForVD = parentNodeForVD.getParentNode().get();
                        }
                    }
                }
                if (node.getChildNodes().size() == 1) {
                    if (node.getChildNodes().get(0).getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                        Node potentialNode = node.getChildNodes().get(0);
                        if (node.getClass().getName().equals("com.github.javaparser.ast.expr.NameExpr")) {
                            String name = ((NameExpr) node).asNameExpr().getNameAsString();
                            if (potentialNode.getRange().isPresent()) {
                                variableNameMap.put(name, potentialNode.getRange().get());
                            }
                        }
                    }
                }
                if (node instanceof FieldAccessExpr) {
                    for (Node ni : node.getChildNodes()) {
                        if (ni.getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                            Node potentialNode = ni;
                            String name = ((SimpleName) potentialNode).asString();
                            variableNameMap.put(name, potentialNode.getRange().get());
                        }
                    }
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                    nodesList.add(node1);
                }
            }
            for (String variableName : variableNameMap.keySet()) {
                Range variableRange = variableNameMap.get(variableName);
                boolean contain = false;
                for (VariableDeclarator variableDeclarator : localMap.keySet()) {
                    if (variableDeclarator.getNameAsString().equals(variableName)) {
                        Range blockRange = localMap.get(variableDeclarator);
                        if (blockRange.contains(variableRange)) {
                            contain = true;
                            break;
                        }
                    }
                }
                if (contain == false) {
                    set.add(variableName);
                }
            }
            for (Node node : nodesList) {
                if (node instanceof ThisExpr) {
                    if (node.getParentNode().isPresent()) {
                        Node parentNode = ((ThisExpr) node).asThisExpr().getParentNode().get();
                        System.out.println(parentNode);
                        if (parentNode.toString().equals("MockitoAnnotations.initMocks(this)")) {
                            parentNode.replace(node, new NameExpr("new " +
                                    javaFile.getCurCI().getName().toString() + "()"));
                        } else if (parentNode.toString().equals("this.getClass()")) {
                            parentNode.replace(node, new NameExpr(
                                    javaFile.getCurCI().getName().toString() + ".class"));
                        } else {
                            parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()));
                        }
                    }
                } else if (flag && node instanceof SuperExpr) {
                    if (node.getParentNode().isPresent()) {
                        Node parentNode = ((SuperExpr) node).asSuperExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(javaFile.getExtendedJavaFile().getCurCI().getNameAsString()));
                    }
                }
            }
        }
        return set;
    }

    protected Set<String> getRelatedFields(FieldDeclaration fd, JavaFile javaFile) {
        Set<String> set = new HashSet<>();
        Map<String, Range> variableNameMap = new HashMap<>();
        Map<VariableDeclarator, Range> localMap = new HashMap<>();
        List<Node> nodesList = new LinkedList<>();
        Queue<Node> nodes = new ArrayDeque<>();
        nodes.add(fd);
        while(!nodes.isEmpty()) {
            Node node = nodes.peek();
            // Assume there are no blocks inside each field declarations
	    if (node.getChildNodes().size() == 1) {
                if (node.getChildNodes().get(0).getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                    Node potentialNode = node.getChildNodes().get(0);
                    if (node.getClass().getName().equals("com.github.javaparser.ast.expr.NameExpr")) {
                        String name = ((NameExpr) node).asNameExpr().getNameAsString();
                        variableNameMap.put(name, potentialNode.getRange().get());
                    }
                }
            }
            if (node instanceof FieldAccessExpr) {
                for (Node ni : node.getChildNodes()) {
                    if (ni.getClass().getName().equals("com.github.javaparser.ast.expr.SimpleName")) {
                        Node potentialNode = ni;
                        String name = ((SimpleName) potentialNode).asString();
                        variableNameMap.put(name, potentialNode.getRange().get());
                    }
                }
            }
            nodes.poll();
            for (Node node1 : node.getChildNodes()) {
                nodes.add(node1);
                nodesList.add(node1);
            }
        }
	for (String variableName : variableNameMap.keySet()) {
	    // Currently, there are no filters.
            set.add(variableName);
        }
        return set;
    }

    protected Set<String> getRelatedMethods(MethodDeclaration md) {
	Set<String> set = new HashSet<>();
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
                    set.add(((MethodCallExpr) node).asMethodCallExpr().getName().asString());
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
        return set;
    }

    protected void changeMethods(MethodDeclaration md, JavaFile javaFile) {
        for (Statement stmt : md.getBody().get().getStatements()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(stmt);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
   	                if (node.toString().equals("getClass()")) {
   	                    System.out.println(node);
 	                    Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()  + ".class"));
                    } /* else if (node.toString().endsWith(".getClass()")) {
                        Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        for (Node child : ((MethodCallExpr) node).asMethodCallExpr().getChildNodes()) {
                            System.out.println(child.toString());
                            System.out.println(child.getClass());// com.github.javaparser.ast.expr.NameExpr
                        }
                        String str = node.toString().replace(".getClass()", ".class");
                        parentNode.replace(node, new NameExpr(str));
                    } */
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
        return;
    }

    protected void changeFields(FieldDeclaration fd, JavaFile javaFile) {
        for (VariableDeclarator vd : fd.getVariables()) {
            Queue<Node> nodes = new ArrayDeque<>();
            nodes.add(vd);
            while(!nodes.isEmpty()) {
                Node node = nodes.peek();
                if (node.getClass().getName().equals("com.github.javaparser.ast.expr.MethodCallExpr")) {
                    if (node.toString().equals("getClass()")) {
                        Node parentNode = ((MethodCallExpr) node).asMethodCallExpr().getParentNode().get();
                        parentNode.replace(node, new NameExpr(javaFile.getCurCI().getName().toString()  + ".class"));
                    }
                }
                nodes.poll();
                for (Node node1 : node.getChildNodes()) {
                    nodes.add(node1);
                }
            }
        }
        return;
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

    private boolean sameTestClass(String test1, String test2) {
        return test1.substring(0, test1.lastIndexOf('.')).equals(test2.substring(0, test2.lastIndexOf('.')));
    }

    private int statementsSize(NodeList<Statement> stmts) {
        int size = 0;
        for (Statement stmt : stmts) {
            // Take care of try statement block
            if (stmt instanceof TryStmt) {
                size += ((TryStmt) stmt).getTryBlock().getStatements().size();
            } else {
                size++;
            }
        }
        return size;
    }

    private List<Path> testSources() throws IOException {
        final List<Path> testFiles = new ArrayList<>();
        try (final Stream<Path> paths = Files.walk(Paths.get(mavenProject.getBuild().getTestSourceDirectory()))) {
            paths.filter(Files::isRegularFile)
                    .forEach(testFiles::add);
        }
        return testFiles;
    }

    private List<Path> wholeTestSources() throws IOException {
        final List<Path> testFiles = new ArrayList<>();
        MavenProject upperProject = mavenProject;
	    while (upperProject.hasParent()) {
            if (upperProject.getParent() == null || upperProject.getParent().getBasedir() == null) {
                break;
            }
            upperProject = upperProject.getParent();
        }
        // System.out.println(upperProject.getBuild().getSourceDirectory());
        if (upperProject.getCollectedProjects() != null && upperProject.getCollectedProjects().size() > 0) {
            for (MavenProject mp : upperProject.getCollectedProjects()) {
                try (final Stream<Path> paths = Files.walk(Paths.get(mp.getBuild().getTestSourceDirectory()))) {
                    paths.filter(Files::isRegularFile)
                            .forEach(testFiles::add);
                } catch (Exception ex) {
                    // ex.printStackTrace();
                }
            }
        } else {
            try (final Stream<Path> paths = Files.walk(Paths.get(upperProject.getBuild().getTestSourceDirectory()))) {
                paths.filter(Files::isRegularFile)
                        .forEach(testFiles::add);
            } catch (Exception ex) {
		        // ex.printStackTrace();
            }
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

    /* private void backup1(final JavaFile javaFile, final String extensions) throws IOException {
        final Path path = ParserPathManager.backupPath1(javaFile.path(), extensions);
        Files.copy(javaFile.path(), path, StandardCopyOption.REPLACE_EXISTING);
    } */

    private void backup1(final JavaFile javaFile, final String extensions) throws IOException {
        final Path path = ParserPathManager.backupPath1(javaFile.path(), extensions);
        final Path path2 = ParserPathManager.backupPath(javaFile.path());
        // copy the backup file(ends with *orig) to path2
        Files.copy(path2, path, StandardCopyOption.REPLACE_EXISTING);
    }


    private void restore(final JavaFile javaFile) throws IOException {
        final Path path = ParserPathManager.backupPath(javaFile.path());
        Files.copy(path, javaFile.path(), StandardCopyOption.REPLACE_EXISTING);
    }

    private NodeList<Statement> getCodeFromAnnotatedMethod(final String testClassName, final JavaFile javaFile, final String annotation) throws Exception {
        NodeList<Statement> stmts = NodeList.nodeList();

        // Determine super classes, to be used for later looking up helper methods
        Class testClass = projectClassLoader().loadClass(testClassName);
        List<Class> superClasses = new ArrayList<>();
        Class currClass = testClass;
        while (currClass != null) {
            superClasses.add(currClass);
            currClass = currClass.getSuperclass();
        }

        // If the test class is a subclass of JUnit 3's TestCase, then there is no annotation, just handle setUp and tearDown
        boolean isJUnit3 = false;
        for (Class clazz : superClasses) {
            // System.out.println(clazz);
            if (clazz.toString().equals("class junit.framework.TestCase")) {
                isJUnit3 = true;
                break;
            }
        }
        // In JUnit 3 mode, try to get statements in setUp/tearDown only if in local class; otherwise put in a call to method if in superclass
        if (isJUnit3) {
            // Check if the test class had defined a setUp/tearDown
            String methName = "";
            for (Method meth : testClass.getDeclaredMethods()) {
                if (annotation.equals("@org.junit.Before")) {
                    if (meth.getName().equals("setUp")) {
                        methName = "setUp";
                        break;
                    }
                } else if (annotation.equals("@org.junit.After")) {
                    if (meth.getName().equals("tearDown")) {
                        methName = "tearDown";
                        break;
                    }
                }
            }
            if (!methName.equals("")) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
                return stmts;   // Finished getting all the statements
            }

            // If reached here, means should go over super classes to see if one of these methods is even defined
            for (Class clazz : superClasses) {
                for (Method meth : clazz.getDeclaredMethods()) {
                    if (annotation.equals("@org.junit.Before")) {
                        if (meth.getName().equals("setUp")) {
                            stmts.add(new ExpressionStmt(new MethodCallExpr(null, "setUp")));
                            return stmts;
                        }
                    } else if (annotation.equals("@org.junit.After")) {
                        if (meth.getName().equals("tearDown")) {
                            stmts.add(new ExpressionStmt(new MethodCallExpr(null, "tearDown")));
                            return stmts;
                        }
                    }
                }
            }
        }

        // Iterate through super classes going "upwards", starting with this test class, to get annotated methods
        // If already seen a method of the same name, then it is overriden, so do not include
        List<String> annotatedMethods = new ArrayList<>();
        List<String> annotatedMethodsLocal = new ArrayList<>();
        for (Class clazz : superClasses) {
            for (Method meth : clazz.getDeclaredMethods()) {
                for (Annotation anno : meth.getDeclaredAnnotations()) {
                    if (anno.toString().equals(annotation + "()")) {
                        if (!annotatedMethods.contains(meth.getName())) {
                            annotatedMethods.add(meth.getName());
                        }
                        if (clazz.equals(testClass)) {
                            annotatedMethodsLocal.add(meth.getName());
                        }
                    }
                }
            }
        }
        annotatedMethods.removeAll(annotatedMethodsLocal);

        // For Before, go last super class first, then inline the statements in test class
        if (annotation.equals("@org.junit.Before")) {
            for (int i = annotatedMethods.size() - 1; i >= 0; i--) {
                stmts.add(new ExpressionStmt(new MethodCallExpr(null, annotatedMethods.get(i))));
            }
            for (String methName : annotatedMethodsLocal) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
            }
        } else {
            // For After, inline the statements in test class, then go first super class first
            for (String methName : annotatedMethodsLocal) {
                MethodDeclaration method = javaFile.findMethodDeclaration(testClassName + "." + methName);
                Optional<BlockStmt> body = method.getBody();
                if (body.isPresent()) {
                    if (method.getDeclarationAsString(false, true, false).contains("throws ")) {
                        // Wrap the body inside a big try statement to suppress any exceptions
                        ClassOrInterfaceType exceptionType = new ClassOrInterfaceType().setName(new SimpleName("Throwable"));
                        CatchClause catchClause = new CatchClause(new Parameter(exceptionType, "ex"), new BlockStmt());
                        stmts.add(new TryStmt(new BlockStmt(body.get().getStatements()), NodeList.nodeList(catchClause), new BlockStmt()));
                    } else {
                        stmts.addAll(body.get().getStatements());
                    }
                }
            }
            for (int i = 0; i < annotatedMethods.size() ; i++) {
                stmts.add(new ExpressionStmt(new MethodCallExpr(null, annotatedMethods.get(i))));
            }
        }

        return stmts;
    }
}
