package edu.utexas.ece.sa.tools.utility;

import edu.illinois.cs.testrunner.data.results.TestResult;
import edu.illinois.cs.testrunner.runner.Runner;

import java.util.*;

public class ShuffleOrdersUtils {
    // Total test order shuffle times
    private static int shuffleTimes = 5;

    public static List<String> shuffleAllTests(List<String> originalTests, Set<String> failedTests, Runner runner){
        List<String> order = new LinkedList<>(originalTests);
        List<String> bestOrder = new LinkedList<>(originalTests);
        Set<List<String>> orders = new HashSet<>();
        orders.add(new LinkedList<>(originalTests));
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
                failedTests = curFailedTests;
            }
        }
        if (hasBetterOrder) {
            System.out.println("THERE IS A BETTER ORDER THAN ORIGINAL!");
            System.out.println("THE BEST ORDER IN THIS CLASS IS: ");
            System.out.println(bestOrder);
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
