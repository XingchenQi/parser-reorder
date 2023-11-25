package edu.utexas.ece.sa.tools.utility;

import edu.illinois.cs.testrunner.data.results.TestResult;
import edu.illinois.cs.testrunner.runner.Runner;
import scala.Int;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShuffleOrdersUtils {
    // Total test order shuffle times
    private static int shuffleTimes = 5;
    private static HashMap<List<String>,Set<String>> orderFailedTestsCache=new HashMap<>();
    private static HashSet<String> allTests=new HashSet<>();
    public static <T> List<List<T>> generatePermutations(List<T> list) {
        List<List<T>> results = new ArrayList<>();
        backtrack(list, new LinkedList<>(), results);
        return results;
    }

    private static <T> void backtrack(List<T> nums, List<T> tempList, List<List<T>> results) {
        if (tempList.size() == nums.size()) {
            results.add(new ArrayList<>(tempList));
        } else {
            for (int i = 0; i < nums.size(); i++) {
                if (tempList.contains(nums.get(i))) continue; // element already exists, skip
                tempList.add(nums.get(i));
                backtrack(nums, tempList, results);
                tempList.remove(tempList.size() - 1);
            }
        }
    }

    public static int runTestsInOrderCli(List<String> testOrder) throws IOException, InterruptedException, IOException {
        System.out.println("RUNNING RESULTS WITH ORDER: " + testOrder);

        StringJoiner tests = new StringJoiner(",");
        for (String test : testOrder) {
            int lastDotIndex = test.lastIndexOf('.');
            if (lastDotIndex != -1) {
                String modifiedTest = test.substring(0, lastDotIndex) + "#" + test.substring(lastDotIndex + 1);
                tests.add(modifiedTest);
            } else {
                tests.add(test);
            }
        }

        String command = "mvn test -Dsurefire.runOrder=testorder -Dtest=" + tests.toString();
        System.out.println(command);

        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        Set<String> curFailedTests = new HashSet<>();
        int skippedTests = 0;
        Pattern failurePattern = Pattern.compile("\\[ERROR\\] (.*)\\s+Time elapsed:.*<<< FAILURE!");
        Pattern errorPattern = Pattern.compile("\\[ERROR\\] (.*)\\s+Time elapsed:.*<<< ERROR!");
        Pattern skippedPattern = Pattern.compile("\\[INFO\\] Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: (\\d+)");

        System.out.println("Start Printing test results\n");

        while ((line = reader.readLine()) != null) {
            //System.out.println(line);
            Matcher failureMatcher = failurePattern.matcher(line);
            Matcher errorMatcher = errorPattern.matcher(line);
            Matcher skippedMatcher = skippedPattern.matcher(line);
            if (failureMatcher.find()) {
                String testName = failureMatcher.group(1).replace("#", ".").trim(); // Ensure no leading/trailing whitespace
                System.out.println(testName);
                if(allTests.contains(testName))
                    curFailedTests.add(testName); // Capture the failed test name
            }
            if (errorMatcher.find()) {
                String testName = errorMatcher.group(1).replace("#", ".").trim(); // Ensure no leading/trailing whitespace
                System.out.println(testName);
                if(allTests.contains(testName))
                    curFailedTests.add(testName); // Capture the failed test name
            }
            if (skippedMatcher.find()) {
                skippedTests += Integer.parseInt(skippedMatcher.group(1)); // Parse the number of skipped tests
            }
        }

        orderFailedTestsCache.put(new LinkedList<>(testOrder), new HashSet<>(curFailedTests));

        System.out.println("RUNNING RESULTS WITH ORDER FAILED CNT: " + curFailedTests.size());
        System.out.println("RUNNING RESULTS WITH ORDER SKIPPED CNT: " + skippedTests);
        System.out.println("FROM ALL TESTS: " + testOrder.size());

        return curFailedTests.size();
    }

    public static int runTestsInOrder(List<String> testOrder, Runner runner) {
        //System.out.println("RUNNING RESULTS WITH ORDER: " + testOrder);
        Map<String, TestResult> newResults = runner.runList(testOrder).get().results();
        Set<String> curFailedTests = new HashSet<>();
        int skippedTests=0;
        for (String key : newResults.keySet()) {
            TestResult testResult = newResults.get(key);
            if (testResult.result().toString().equals("FAILURE") ||
                    testResult.result().toString().equals("ERROR")) {
                curFailedTests.add(key);
            }
            if (testResult.result().toString().equals("SKIPPED")) {
                skippedTests++;
                continue;
            }
        }
        orderFailedTestsCache.put(new LinkedList<String> (testOrder), new HashSet<String> (curFailedTests));
        System.out.println("RUNNING RESULTS WITH ORDER FAILED CNT: " + curFailedTests.size());
        System.out.println("RUNNING RESULTS WITH ORDER SKIPPED CNT: " + skippedTests);
        System.out.println("FROM ALL TESTS: " + testOrder.size());
        return curFailedTests.size();
    }

    public static int getFailedByBruteForce(List<String> originalTests,Runner runner) throws IOException, InterruptedException {
        List<List<String>> permutations = generatePermutations(originalTests);
        int minFailures=originalTests.size();
        for (int i=0;i<permutations.size();i++) {
            List<String> testOrder=permutations.get(i);
            int failures = runTestsInOrder(testOrder,runner);
            //int failures=runTestsInOrderCli(testOrder);
            if (failures < minFailures) {
                minFailures = failures;
            }
        }
        return minFailures;
    }

    public static int getFailedByRandom(List<String> originalTests,Runner runner) throws IOException, InterruptedException {
        Set<List<String>> orders = new HashSet<>();
        List<String> order = new LinkedList<>(originalTests);
        int minFailures=originalTests.size();
        for (int i=0;i<1000;i++) {
            while (orders.contains(order)) {
                long generatedSeed = new Random().nextLong();
                // Use the generated seed for reproducibility
                Random random = new Random(generatedSeed);

                Collections.shuffle(order, random);
            }
            orders.add(new LinkedList<>(order));
            int failures = runTestsInOrder(order,runner);
            //int failures=runTestsInOrderCli(order);
            if (failures < minFailures) {
                minFailures = failures;
            }
        }
        return minFailures;
    }

    public static int getFailedCntNew(List<String> originalTests, Set<String> failedTests, Runner runner, AtomicInteger cnt,HashSet<List<String>> visited) throws IOException, InterruptedException {
        if(failedTests.size()==0){
            return 0;
        }
        if(cnt.get()>1000){
            return failedTests.size();
        }
        visited.add(originalTests);
        int minTests=failedTests.size();
        for(String failedTest:failedTests){
            if(minTests==0)
                return 0;
            List<String> tempOrder = new LinkedList<>(originalTests);
            tempOrder.remove(failedTest);
            tempOrder.add(0,failedTest);
            if(visited.contains(tempOrder)){
                continue;
            }
            int failCount=0;
            Set<String> curFailedTests = new HashSet<>();
            if(orderFailedTestsCache.containsKey(tempOrder)){

                curFailedTests=orderFailedTestsCache.get(tempOrder);
                failCount=curFailedTests.size();
            }else{
                failCount=runTestsInOrder(tempOrder,runner);
                //failCount=runTestsInOrderCli(tempOrder);
                curFailedTests=orderFailedTestsCache.get(tempOrder);
            }
            System.out.println("RUNNING RESULTS WITH ORDER: " + tempOrder);
            cnt.incrementAndGet();
            System.out.println("RUNNING RESULTS WITH NEW ORDER: " + failCount);
            System.out.println("Failed Tests: "+ curFailedTests);
            if(failCount<failedTests.size()){
                minTests=Math.min(minTests,getFailedCntNew(tempOrder,curFailedTests,runner,cnt,visited));
            }
        }
        return minTests;
    }

    public static List<String> shuffleAllTests(List<String> originalTests, Set<String> failedTests, Runner runner) throws IOException, InterruptedException {
        for(String test:originalTests){
            allTests.add(test);
        }

        System.out.println("ORIGINAL TESTS ORDER: " + originalTests);
        System.out.println("INITIAL FAILED COUNTS: " + failedTests.size());
        int leastFailedCnt;
        if(originalTests.size()>7){
            leastFailedCnt=getFailedByRandom(originalTests, runner);
            System.out.println("=====START PRINTING FAILED COUNTS IN RANDOM 1000=====\n");
            System.out.println("Least failed cnt: "+ leastFailedCnt+" From all tests: "+originalTests.size());
        }else{
            leastFailedCnt=getFailedByBruteForce(originalTests,runner);
            System.out.println("=====START PRINTING FAILED COUNTS IN BRUTE FORCE=====\n");
            System.out.println("Least failed cnt: "+ leastFailedCnt+" From all tests: "+originalTests.size());
        }

        if(orderFailedTestsCache.containsKey(originalTests)){
            failedTests=orderFailedTestsCache.get(originalTests);
        }


        int num=5;
        if(originalTests.size()<=2){
            num=originalTests.size();
        }

        List<String> newOrder = new LinkedList<>(originalTests);

        Set<List<String>> triedOrders = new HashSet<>();
        Set<String> newFailedTests = new HashSet<>(failedTests);
        for(int i=0;i<num;i++){
            System.out.println("=====START PRINTING FAILED COUNTS IN NEW ORDER=====\n");
            triedOrders.add(newOrder);
            AtomicInteger rounds = new AtomicInteger(0);
            HashSet<List<String>> visited=new HashSet<>();
            System.out.println("TRIED TESTS ORDER: " + newOrder);
            System.out.println("Initial Failed Count: " + newFailedTests.size());
            System.out.println("Least failed cnt: "+getFailedCntNew(newOrder, newFailedTests, runner,rounds,visited) +" From all tests: "+newOrder.size()+"\n");
            System.out.println("Total Rounds Tried: "+rounds);
            while(triedOrders.contains(newOrder)){
                Collections.shuffle(newOrder);
            }
            runTestsInOrder(newOrder,runner);
            //runTestsInOrderCli(newOrder);
            newFailedTests=orderFailedTestsCache.get(newOrder);
        }

        System.out.println("=====END PRINTING FAILED COUNTS IN NEW ALGO=====\n");

        List<String> order = new LinkedList<>(originalTests);
        List<String> bestOrder = new LinkedList<>(originalTests);
        Set<List<String>> orders = new HashSet<>();
        Set<String> initialFailedTests = new HashSet<>(failedTests);
        int threshold = initialFailedTests.size();

        int size = order.size();
        if (size == 1) {
            shuffleTimes = 0;
        } else if (size == 2) {
            shuffleTimes = 1;
        }

        boolean hasBetterOrder = false;
        int i = 0;
        while (i < shuffleTimes) {
            Set<String> curFailedTests = new HashSet<>();
            // Generate a seed and print it
            long generatedSeed = new Random().nextLong();
            // System.out.println("Generated seed: " + generatedSeed);

            // Use the generated seed for reproducibility
            Random random = new Random(generatedSeed);

            Collections.shuffle(order, random);
            if (orders.contains(order)) {
                System.out.println("CURRENT TESTS ORDER EXISTS, SKIPPING.....");
                continue;
            }
            i++;
            orders.add(new LinkedList<>(order));
            System.out.println("NEW TESTS ORDER: " + order);
            Map<String, TestResult> newResultsRandom = runner.runList(order).get().results();
            System.out.println("RUNNING RESULTS WITH NEW ORDER: " + newResultsRandom);
            int failedCnt = 0;
            boolean skipped = false;
            for (String key : newResultsRandom.keySet()) {
                TestResult testResult = newResultsRandom.get(key);
                if (testResult.result().toString().equals("FAILURE")) {
                    failedCnt++;
                    curFailedTests.add(key);
                }
                if (testResult.result().toString().equals("ERROR")) {
                    failedCnt++;
                    curFailedTests.add(key);
                }
                if (testResult.result().toString().equals("SKIPPED")) {
                    skipped = true;
                    break;
                }
            }
            if (skipped) {
                continue;
            }
            if (failedCnt < threshold) {
                hasBetterOrder = true;
                bestOrder = new LinkedList<>(order);
                System.out.println("FOUND BETTER ORDER WITH MORE PASSES!");
                System.out.println("NEW TESTS ORDER: " + order);
                threshold = failedCnt;
                failedTests = new HashSet<>(curFailedTests);
            }
        }
        if (hasBetterOrder) {
            System.out.println("THERE IS A BETTER ORDER THAN ORIGINAL!");
            System.out.println("THE BEST ORDER IN THIS CLASS IS: ");
            System.out.println(bestOrder);
            System.out.println("NEW FAILED COUNTS: " + threshold);
        } else {
            failedTests = initialFailedTests;
            System.out.println("NO BETTER ORDER THAN ORIGINAL!");
        }
        shuffleTimes = 5;
        return bestOrder;
    }

    public static void checkTestsOrder(Map<String, List<String>> splitTests, Runner runner){
        System.out.println("=====START CHECKING TESTS ORDER AFTER SPLIT=====\n");

        splitTests.forEach((key, value) -> System.out.println(key + " " + value));
        List<String> allTests = new ArrayList<>();
        List<String> allClasses = new ArrayList<>();
        for(String newClass : splitTests.keySet()){
            allClasses.add(newClass);
            List<String> testsInOrder = splitTests.get(newClass);
            allTests.addAll(testsInOrder);
        }
        HashSet<List<String>> allOrders = new HashSet<>();
        allOrders.add(new ArrayList<>(allTests));
        System.out.println("ALL TESTS ORDER: " + allTests);
        Set<String> failedTests = new HashSet<>();
        boolean foundFail = false;
        Map<String, TestResult> newResultsInOrder = runner.runList(allTests).get().results();
        for (String key : newResultsInOrder.keySet()) {
            TestResult testResult = newResultsInOrder.get(key);
            if (testResult.result().toString().equals("FAILURE")) {
                System.out.println("FOUND FAILURE IN CURRENT ORDER! " + key);
                failedTests.add(key);
                foundFail = true;
            }
            if (testResult.result().toString().equals("ERROR")) {
                System.out.println("FOUND ERROR IN CURRENT ORDER! " + key);
                failedTests.add(key);
                foundFail = true;
            }
        }
        System.out.println("RUNNING RESULTS WITH ALL TESTS IN ABOVE ORDER: " + newResultsInOrder);
        for (int i = 0; i < shuffleTimes; i++) {
            // Generate a seed and print it
            long generatedSeed = new Random().nextLong();
            System.out.println("Generated seed: " + generatedSeed);

            // Use the generated seed for reproducibility
            Random random = new Random(generatedSeed);

            Collections.shuffle(allClasses, random);

            System.out.println("NEW CLASSES ORDER: " + allClasses);
            for (int j = 0; j < shuffleTimes; j++) {
                List<String> gatherAllTests = new ArrayList<>();
                for (String curClass : allClasses){
                    List<String> testsByClass = splitTests.get(curClass);
                    Collections.shuffle(testsByClass, random);
                    gatherAllTests.addAll(testsByClass);
                }
                if (allOrders.contains(gatherAllTests)) {
                    System.out.println("CURRENT TESTS ORDER EXISTS, SKIPPING.....");
                    continue;
                }
                allOrders.add(gatherAllTests);
                System.out.println("NEW TESTS ORDER: " + gatherAllTests);
                Map<String, TestResult> newResultsRandom = runner.runList(gatherAllTests).get().results();
                System.out.println("RUNNING RESULTS WITH ALL TESTS SHUFFLE: " + newResultsRandom);

                for (String key : newResultsRandom.keySet()) {
                    TestResult testResult = newResultsRandom.get(key);
                    if (testResult.result().toString().equals("FAILURE")) {
                        System.out.println("FOUND FAILURE IN CURRENT ORDER! " + key);
                        failedTests.add(key);
                        foundFail = true;
                    }
                    if (testResult.result().toString().equals("ERROR")) {
                        System.out.println("FOUND ERROR IN CURRENT ORDER! " + key);
                        failedTests.add(key);
                        foundFail = true;
                    }
                }
            }
        }
        if (foundFail) {
            System.out.println("==========FOUND FAILURES/ERRORS IN CURRENT ABOVE ORDER! " +
                    "PLEASE REMAIN THE ORIGINAL ORDER!==========");
            System.out.println("FOUND " + failedTests.size() + " OD TESTS!!!");
            System.out.println("Failed Tests: "+failedTests);
        }
    }
}
