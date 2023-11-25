package edu.utexas.ece.sa.tools.utility;

import edu.illinois.cs.testrunner.data.results.TestResult;
import edu.utexas.ece.sa.tools.parser.JavaFile;
import edu.utexas.ece.sa.tools.parser.ParserPathManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;

public class Utils {
    public static void backup1(final JavaFile javaFile, final String extensions) {
        try {
            final Path path = ParserPathManager.backupPath1(javaFile.path(), extensions);
            final Path path2 = ParserPathManager.backupPath(javaFile.path());
            // copy the backup file(ends with *orig) to path2
            Files.copy(path2, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
    }

    public static void obtainLastTestResults(Map<String, TestResult> map, Set<String> failedTests) {
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
                //System.exit(0);
            }
        }
    }
}
