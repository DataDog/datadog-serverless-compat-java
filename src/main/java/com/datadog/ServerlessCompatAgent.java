package com.datadog;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

enum CloudEnvironment {
    AZURE_FUNCTION,
    AZURE_SPRING_APP,
    GOOGLE_CLOUD_RUN_FUNCTION_1ST_GEN,
    GOOGLE_CLOUD_RUN_FUNCTION_2ND_GEN,
    UNKNOWN
}

public class ServerlessCompatAgent {
    private static final String ddLogLevel = System.getenv().getOrDefault("DD_LOG_LEVEL", "INFO").toUpperCase();

    private static final Logger log = initLogger(ddLogLevel);

    private static Logger initLogger(String logLevel) {
        if ("OFF".equals(logLevel)) {
            return NOPLogger.NOP_LOGGER;
        } else {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
                    mapDdLogLevelToSlf4jLogLevel(logLevel));
            return LoggerFactory.getLogger(ServerlessCompatAgent.class);
        }
    }

    private static String mapDdLogLevelToSlf4jLogLevel(String ddLogLevel) {
        return "CRITICAL".equals(ddLogLevel) ? "ERROR" : ddLogLevel;
    }

    private static final String os = System.getProperty("os.name").toLowerCase();
    private static final String binaryPath = System.getenv("DD_SERVERLESS_COMPAT_PATH");
    static final String DOGSTATSD_PORT_ENV = "DD_DOGSTATSD_PORT";
    static final String DEFAULT_DOGSTATSD_PORT = "8125";

    public static boolean isWindows() {
        return os.contains("win");
    }

    public static boolean isLinux() {
        return os.contains("linux");
    }

    public static CloudEnvironment getEnvironment() {
        return getEnvironment(System.getenv());
    }

    static CloudEnvironment getEnvironment(Map<String, String> env) {
        List<CloudEnvironment> detected = new ArrayList<>();

        if (env.get("FUNCTIONS_EXTENSION_VERSION") != null
                && env.get("FUNCTIONS_WORKER_RUNTIME") != null) {
            detected.add(CloudEnvironment.AZURE_FUNCTION);
        }

        if (env.get("ASCSVCRT_SPRING__APPLICATION__NAME") != null) {
            detected.add(CloudEnvironment.AZURE_SPRING_APP);
        }

        if (env.get("FUNCTION_NAME") != null && env.get("GCP_PROJECT") != null) {
            // Set by Google Cloud Functions for older runtimes
            detected.add(CloudEnvironment.GOOGLE_CLOUD_RUN_FUNCTION_1ST_GEN);
        } else if (env.get("K_SERVICE") != null && env.get("FUNCTION_TARGET") != null) {
            // Set by Google Cloud Functions for newer runtimes
            detected.add(CloudEnvironment.GOOGLE_CLOUD_RUN_FUNCTION_2ND_GEN);
        }

        if (detected.isEmpty()) {
            return CloudEnvironment.UNKNOWN;
        }
        if (detected.size() > 1) {
            log.error("Multiple cloud environments detected: {}", detected);
            return CloudEnvironment.UNKNOWN;
        }

        CloudEnvironment environment = detected.get(0);
        return environment;
    }

    public static String getPackageVersion() {
        String packageVersion;

        try {
            packageVersion = ServerlessCompatAgent.class.getPackage().getImplementationVersion();
        } catch (Exception e) {
            log.error("Unable to identify package version", e);
            packageVersion = "unknown";
        }

        return packageVersion == null ? "unknown" : packageVersion;
    }

    public static boolean isAzureFlexWithoutDDAzureResourceGroup() {
        return "FlexConsumption".equals(System.getenv("WEBSITE_SKU"))
                && System.getenv("DD_AZURE_RESOURCE_GROUP") == null;
    }

    /**
     * Chooses this function's named pipes. The binary always gets them, so it warns instead of
     * panicking when another binary on the instance owns a port.
     *
     * <p>dd-java-agent gets them, as system properties, only when the gate says it can use pipes;
     * the properties must be set before its premain runs. Otherwise the tracer keeps what it
     * inherited. It learns a pipe name only from its own configuration, so the binary's pipes
     * can't reach an older tracer.
     *
     * @return the chosen pipe names for the binary
     */
    static NamedPipeNames configureNamedPipes() {
        return configureNamedPipes(TracerVersionGate.check(), System.getenv(), System.getProperties());
    }

    static NamedPipeNames configureNamedPipes(TracerVersionGate gate, Map<String, String> env,
            Properties systemProperties) {
        NamedPipeNames pipeNames = NamedPipeNames.choose(env, systemProperties);
        for (String warning : pipeNames.warnings) {
            log.warn(warning);
        }

        if (!gate.passed) {
            log.info("Tracer named pipes are off, so dd-java-agent keeps its inherited trace and DogStatsD "
                    + "settings: {}. The binary still listens on pipes {} and {}, TCP, and UDP.",
                    gate.reason, pipeNames.tracePipeName, pipeNames.dogstatsdPipeName);
            return pipeNames;
        }
        log.debug("Tracer named pipes are on: {}", gate.reason);

        systemProperties.setProperty(NamedPipeNames.TRACE_PIPE_NAME_PROPERTY, pipeNames.tracePipeName);
        systemProperties.setProperty(NamedPipeNames.DOGSTATSD_PIPE_NAME_PROPERTY, pipeNames.dogstatsdPipeName);
        log.info("Configured named pipes - Trace: {}, DogStatsD: {}",
                pipeNames.tracePipeName, pipeNames.dogstatsdPipeName);
        return pipeNames;
    }

    /**
     * Passes the pipe names to the binary and asks it to keep UDP too. With a pipe, the binary
     * listens on UDP only when {@code DD_DOGSTATSD_PORT} is set. A JVM can't change its own
     * environment, so DogStatsD clients that don't read {@code dd.dogstatsd.pipe.name}, or can't
     * open the pipe, still send to UDP 8125. A port the customer set wins.
     */
    static void addPipeSettings(Map<String, String> binaryEnvironment, NamedPipeNames pipeNames) {
        binaryEnvironment.put(NamedPipeNames.TRACE_WINDOWS_PIPE_NAME_ENV, pipeNames.tracePipeName);
        binaryEnvironment.put(NamedPipeNames.DOGSTATSD_WINDOWS_PIPE_NAME_ENV, pipeNames.dogstatsdPipeName);
        binaryEnvironment.putIfAbsent(DOGSTATSD_PORT_ENV, DEFAULT_DOGSTATSD_PORT);
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        CloudEnvironment environment = getEnvironment();
        log.debug("Environment detected: {}", environment);

        if (environment == CloudEnvironment.UNKNOWN) {
            log.error("{} environment detected, will not start the Datadog Serverless Compatibility Layer",
                    environment);
            return;
        }

        final String fileName;
        final String tempDirPath;

        if (isWindows()) {
            log.debug("Detected {}", os);
            fileName = "bin/windows-amd64/datadog-serverless-compat.exe";
            tempDirPath = "C:/local/Temp/datadog";
        } else if (isLinux()) {
            log.debug("Detected {}", os);
            fileName = "bin/linux-amd64/datadog-serverless-compat";
            tempDirPath = "/tmp/datadog";
        } else {
            log.error("Unsupported operating system {}", os);
            return;
        }

        // Check for Azure Flex Consumption functions that don't have the
        // DD_AZURE_RESOURCE_GROUP environment variable set
        if (environment == CloudEnvironment.AZURE_FUNCTION && isAzureFlexWithoutDDAzureResourceGroup()) {
            log.error(
                    "Azure function detected on flex consumption plan without DD_AZURE_RESOURCE_GROUP set. Please set the DD_AZURE_RESOURCE_GROUP environment variable to your resource group name in Azure app settings. Shutting down Datadog Serverless Compatibility Layer.");
            return;
        }

        try (InputStream inputStream = ServerlessCompatAgent.class.getClassLoader()
                .getResourceAsStream(fileName)) {
            if (inputStream == null) {
                log.error("{} not found", fileName);
                return;
            }

            Path tempDir = Paths.get(tempDirPath);
            Files.createDirectories(tempDir);

            Path filePath = Paths.get(fileName);
            Path executableFilePath = tempDir.resolve(filePath.getFileName());

            Files.copy(inputStream, executableFilePath, StandardCopyOption.REPLACE_EXISTING);

            File executableFile = executableFilePath.toFile();
            executableFile.setExecutable(true);

            if (binaryPath != null) {
                log.debug("Detected user configured binary path {}", binaryPath);

                File userExecutableFile = new File(binaryPath);
                userExecutableFile.setExecutable(true);
                executableFile = userExecutableFile;
            }

            String packageVersion = getPackageVersion();
            log.debug("Found package version {}", packageVersion);

            NamedPipeNames pipeNames = null;
            if (environment == CloudEnvironment.AZURE_FUNCTION && isWindows()) {
                pipeNames = configureNamedPipes();
            }

            ProcessBuilder processBuilder = new ProcessBuilder(executableFile.getAbsolutePath());
            processBuilder.environment().put("DD_SERVERLESS_COMPAT_VERSION", packageVersion);
            if (pipeNames != null) {
                addPipeSettings(processBuilder.environment(), pipeNames);
            }
            processBuilder.inheritIO();
            processBuilder.start();
        } catch (Exception e) {
            log.error("Exception when starting {}", fileName, e);
        }
    }
}
