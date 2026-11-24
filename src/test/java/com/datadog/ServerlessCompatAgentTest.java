package com.datadog;

import java.lang.reflect.Method;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

import static org.junit.jupiter.api.Assertions.*;

class ServerlessCompatAgentTest {

    @Test
    void childReceivesWrapperAndEngineVersionsForCustomBinary() {
        File executable = new File("/custom/compat");
        String inheritedWorkerVersion = System.getenv("FUNCTIONS_WORKER_RUNTIME_VERSION");
        ProcessBuilder child = ServerlessCompatAgent.createProcessBuilder(
                executable, "0.25.0");
        assertEquals(executable.getAbsolutePath(), child.command().get(0));
        assertEquals("0.25.0", child.environment().get("DD_SERVERLESS_COMPAT_VERSION"));
        assertEquals("java" + System.getProperty("java.version"), child.environment().get("DD_SERVERLESS_COMPAT_RUNTIME"));
        assertEquals(System.getenv("JAVA_VERSION"), child.environment().get("JAVA_VERSION"));
        assertEquals(inheritedWorkerVersion, child.environment().get("FUNCTIONS_WORKER_RUNTIME_VERSION"));
        assertEquals(inheritedWorkerVersion, System.getenv("FUNCTIONS_WORKER_RUNTIME_VERSION"));
        assertEquals(System.getenv("DD_SERVICE"), child.environment().get("DD_SERVICE"));
    }

    @ParameterizedTest
    @CsvSource({
            "TRACE, TRACE",
            "DEBUG, DEBUG",
            "INFO, INFO",
            "WARN, WARN",
            "ERROR, ERROR",
            "CRITICAL, ERROR",
            "OFF, null"
    })
    void testInitLogger(String ddLogLevel, String expectedSlf4jLevel) throws Exception {
        Method initLoggerMethod = ServerlessCompatAgent.class.getDeclaredMethod("initLogger", String.class);
        initLoggerMethod.setAccessible(true);
        Logger logger = (Logger) initLoggerMethod.invoke(null, ddLogLevel);

        if ("OFF".equals(ddLogLevel)) {
            assertTrue(logger instanceof NOPLogger);
        } else {
            assertEquals(expectedSlf4jLevel,
                    System.getProperty("org.slf4j.simpleLogger.defaultLogLevel"));
        }
    }

    private static Map<String, String> windowsPipeNameEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "trace_pipe");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_pipe");
        return env;
    }

    private static NamedPipeNames pipeNames() {
        return NamedPipeNames.choose(windowsPipeNameEnv(), new Properties(), true);
    }

    @Test
    void configureNamedPipes_passedGateGivesPipesToTracer() {
        TracerVersionGate gate = new TracerVersionGate(true, "test tracer supports pipes", false);
        Properties systemProperties = new Properties();

        NamedPipeNames pipeNames = ServerlessCompatAgent.configureNamedPipes(gate, windowsPipeNameEnv(),
                systemProperties);

        assertEquals("trace_pipe", pipeNames.tracePipeName);
        assertEquals("dogstatsd_pipe", pipeNames.dogstatsdPipeName);
        assertEquals("trace_pipe", systemProperties.getProperty("dd.trace.pipe.name"));
        assertEquals("dogstatsd_pipe", systemProperties.getProperty("dd.dogstatsd.pipe.name"));
    }

    @Test
    void configureNamedPipes_failedGateGivesPipesToBinaryOnly() {
        TracerVersionGate gate = TracerVersionGate.failed("test tracer is too old");
        Properties systemProperties = new Properties();

        NamedPipeNames pipeNames = ServerlessCompatAgent.configureNamedPipes(gate, new HashMap<>(),
                systemProperties);

        // The binary still gets pipes, but an older tracer must never see them: it would hang
        // or throw on a pipe.
        assertNotNull(pipeNames);
        assertTrue(pipeNames.tracePipeName.startsWith("dd_trace_"));
        assertTrue(pipeNames.dogstatsdPipeName.startsWith("dd_dogstatsd_"));
        assertNull(systemProperties.getProperty("dd.trace.pipe.name"));
        assertNull(systemProperties.getProperty("dd.dogstatsd.pipe.name"));
    }

    @Test
    void addPipeSettings_asksBinaryToKeepUdp() {
        Map<String, String> binaryEnvironment = new HashMap<>();

        ServerlessCompatAgent.addPipeSettings(binaryEnvironment, pipeNames());

        assertEquals("trace_pipe", binaryEnvironment.get("DD_APM_WINDOWS_PIPE_NAME"));
        assertEquals("dogstatsd_pipe", binaryEnvironment.get("DD_DOGSTATSD_WINDOWS_PIPE_NAME"));
        // With a pipe, the binary listens on UDP only when the port is set.
        assertEquals("8125", binaryEnvironment.get("DD_DOGSTATSD_PORT"));
    }

    @Test
    void addPipeSettings_keepsCustomerPort() {
        Map<String, String> binaryEnvironment = new HashMap<>();
        binaryEnvironment.put("DD_DOGSTATSD_PORT", "0");

        ServerlessCompatAgent.addPipeSettings(binaryEnvironment, pipeNames());

        assertEquals("0", binaryEnvironment.get("DD_DOGSTATSD_PORT"));
    }

    @Test
    void getEnvironment_returnsUnknownWhenNoVarsSet() {
        assertEquals(CloudEnvironment.UNKNOWN,
                ServerlessCompatAgent.getEnvironment(new HashMap<>()));
    }

    @Test
    void getEnvironment_detectsAzureFunction() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTIONS_EXTENSION_VERSION", "~4");
        env.put("FUNCTIONS_WORKER_RUNTIME", "java");
        assertEquals(CloudEnvironment.AZURE_FUNCTION,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_requiresBothAzureFunctionVars() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTIONS_EXTENSION_VERSION", "~4");
        assertEquals(CloudEnvironment.UNKNOWN,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_detectsAzureSpringApp() {
        Map<String, String> env = new HashMap<>();
        env.put("ASCSVCRT_SPRING__APPLICATION__NAME", "my-app");
        assertEquals(CloudEnvironment.AZURE_SPRING_APP,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_detectsGcpOlderRuntimes() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTION_NAME", "my-function");
        env.put("GCP_PROJECT", "my-project");
        assertEquals(CloudEnvironment.GOOGLE_CLOUD_RUN_FUNCTION_1ST_GEN,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_requiresBothGcpOlderRuntimesVars() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTION_NAME", "my-function");
        assertEquals(CloudEnvironment.UNKNOWN,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_detectsGcpNewerRuntimes() {
        Map<String, String> env = new HashMap<>();
        env.put("K_SERVICE", "my-service");
        env.put("FUNCTION_TARGET", "handler");
        assertEquals(CloudEnvironment.GOOGLE_CLOUD_RUN_FUNCTION_2ND_GEN,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_returnsUnknownWhenMultipleEnvironmentsDetected() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTIONS_EXTENSION_VERSION", "~4");
        env.put("FUNCTIONS_WORKER_RUNTIME", "java");
        env.put("FUNCTION_NAME", "my-function");
        env.put("GCP_PROJECT", "my-project");
        assertEquals(CloudEnvironment.UNKNOWN,
                ServerlessCompatAgent.getEnvironment(env));
    }

    @Test
    void getEnvironment_bareFunctionNameDoesNotTriggerGcpDetection() {
        Map<String, String> env = new HashMap<>();
        env.put("FUNCTION_NAME", "my-function");
        env.put("FUNCTIONS_EXTENSION_VERSION", "~4");
        env.put("FUNCTIONS_WORKER_RUNTIME", "java");
        assertEquals(CloudEnvironment.AZURE_FUNCTION,
                ServerlessCompatAgent.getEnvironment(env));
    }
}
