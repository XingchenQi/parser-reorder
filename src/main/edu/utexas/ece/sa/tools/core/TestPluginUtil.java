package edu.utexas.ece.sa.tools.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import edu.illinois.cs.testrunner.configuration.ConfigProps;
import edu.illinois.cs.testrunner.configuration.Configuration;
import edu.utexas.ece.sa.tools.utility.ProjectWrapper;

public class TestPluginUtil {
    final public static String pluginName = "testplugin";
    final public static String pluginClassName = "testplugin.className";
    final public static String defaultPluginClassName = "edu.illinois.cs.testrunner.coreplugin.TestRunner";
    private static List<URL> pluginCpURLs = null;
    private static String pluginCp = null;
    public static ProjectWrapper project;

    private static void generate() {
        // TODO: When upgrading past Java 8, this will probably no longer work
        // (cannot cast any ClassLoader to URLClassLoader)
        URLClassLoader pluginClassloader = (URLClassLoader) Thread.currentThread().getContextClassLoader();
        pluginCpURLs = Arrays.asList(pluginClassloader.getURLs());
        pluginCp = String.join(File.pathSeparator,
                pluginCpURLs.stream().map((PluginCpUrl) -> PluginCpUrl.getPath()).toArray(String[]::new));
    }

    private static List<URL> pluginClasspathUrls() {
        if (pluginCpURLs == null) {
            generate();
        }

        return pluginCpURLs;
    }

    private static String pluginClasspath() {
        if (pluginCp == null) {
            generate();
        }

        return pluginCp;
    }

    private static void configJavaAgentPath() {
        pluginClasspathUrls().stream().filter((url) -> url.toString().contains("testrunner-maven-plugin") ||
                url.toString().contains("testrunner-gradle-plugin")).forEach((url) -> Configuration.config().setDefault("testplugin.javaagent", url.toString()));
    }

    private static void setDefaults(Configuration configuration) {
        configuration.setDefault(ConfigProps.CAPTURE_STATE, String.valueOf(false));
        configuration.setDefault(ConfigProps.UNIVERSAL_TIMEOUT, String.valueOf(-1));
        configuration.setDefault(ConfigProps.SMARTRUNNER_DEFAULT_TIMEOUT, String.valueOf(6 * 3600));
        configuration.setDefault("testplugin.runner.smart.timeout.multiplier", String.valueOf(4));
        configuration.setDefault("testplugin.runner.smart.timeout.offset", String.valueOf(5));
        configuration.setDefault("testplugin.runner.smart.timeout.pertest", String.valueOf(2));
        configuration.setDefault("testplugin.classpath", pluginClasspath());

        configJavaAgentPath();
    }

    public static void setConfigs(String propertiesPath) throws IOException {
        System.getProperties()
                .forEach((key, value) -> Configuration.config().properties().setProperty(key.toString(), value.toString()));

        if (propertiesPath != null && !propertiesPath.isEmpty()) {
            Configuration.reloadConfig(Paths.get(propertiesPath));
        }

        setDefaults(Configuration.config());
    }
}

