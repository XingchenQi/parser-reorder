package edu.utexas.ece.sa.tools.testobjects

import edu.utexas.ece.sa.tools.data.framework.TestFramework

object GeneralTestClass {
    /**
      * Create a test class from given class name.
      *
      * We must pass in a classloader because we must load EXACTLY the same classes as used by the subject
      * which may differ from the versions used by this plugin/maven
      */
    def create(loader: ClassLoader, clzName: String, framework: TestFramework)
            : Option[GeneralTestClass] = {
        framework.tryGenerateTestClass(loader, clzName)
    }
}

trait GeneralTestClass {
    def tests(): Stream[String]
}
