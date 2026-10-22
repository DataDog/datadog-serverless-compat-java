package com.datadog;

import java.lang.reflect.Method;
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

    private static NamedPipeNames pipeNames() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "trace_pipe");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_pipe");
        return NamedPipeNames.choose(env, new Properties());
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
