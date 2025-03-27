package com.datadog;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerlessCompatAgent {
    private static final Logger log = LoggerFactory.getLogger(ServerlessCompatAgent.class);
    private static final String os = System.getProperty("os.name").toLowerCase();
    private static final String binaryPath = System.getenv("DD_SERVERLESS_COMPAT_PATH");

    public static boolean isWindows() {
        return os.contains("win");
    }

    public static boolean isLinux() {
        return os.contains("linux");
    }

    public static String setPackageVersion() {
        String packageVersion;

        try {
            packageVersion = ServerlessCompatAgent.class.getPackage().getImplementationVersion();
        }
        catch (Exception e) {
            log.error("Unable to identify package version", e);
            packageVersion = "unknown";
        }

        return packageVersion == null ? "unknown" : packageVersion;
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
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

            String packageVersion = setPackageVersion();
            log.debug("Found package version {}", packageVersion);

            ProcessBuilder processBuilder = new ProcessBuilder(executableFile.getAbsolutePath());
            processBuilder.environment().put("DD_SERVERLESS_COMPAT_VERSION", packageVersion);
            processBuilder.inheritIO();
            processBuilder.start();
        } catch (Exception e) {
            log.error("Exception when starting {}", fileName, e);
        }
    }
}
