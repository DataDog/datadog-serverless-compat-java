package com.datadog;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NamedPipeNamesTest {

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    @Test
    void choose_windowsPipeNamesWin() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "apm_windows");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_windows");

        NamedPipeNames names = NamedPipeNames.choose(env, new Properties());

        assertEquals("apm_windows", names.tracePipeName);
        assertEquals("dogstatsd_windows", names.dogstatsdPipeName);
        assertTrue(names.warnings.isEmpty());
    }

    @Test
    void choose_windowsPipeNamesWinOverCustomerSetNames() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "apm_windows");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_windows");
        env.put("DD_TRACE_PIPE_NAME", "trace_env");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("dd.dogstatsd.pipe.name", "dogstatsd_property");

        NamedPipeNames names = NamedPipeNames.choose(env, systemProperties);

        assertEquals("apm_windows", names.tracePipeName);
        assertEquals("dogstatsd_windows", names.dogstatsdPipeName);
    }

    @Test
    void choose_usesCustomerSetEnvironmentVariables() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_TRACE_PIPE_NAME", "trace_env");
        env.put("DD_DOGSTATSD_PIPE_NAME", "dogstatsd_env");

        NamedPipeNames names = NamedPipeNames.choose(env, new Properties());

        assertEquals("trace_env", names.tracePipeName);
        assertEquals("dogstatsd_env", names.dogstatsdPipeName);
        assertTrue(names.warnings.isEmpty());
    }

    @Test
    void choose_usesCustomerSetJvmOptions() {
        Properties systemProperties = new Properties();
        systemProperties.setProperty("dd.trace.pipe.name", "trace_property");
        systemProperties.setProperty("dd.dogstatsd.pipe.name", "dogstatsd_property");

        NamedPipeNames names = NamedPipeNames.choose(new HashMap<>(), systemProperties);

        assertEquals("trace_property", names.tracePipeName);
        assertEquals("dogstatsd_property", names.dogstatsdPipeName);
        assertTrue(names.warnings.isEmpty());
    }

    @Test
    void choose_jvmOptionsWinOverEnvironmentVariablesLikeTheTracer() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_TRACE_PIPE_NAME", "trace_env");
        env.put("DD_DOGSTATSD_PIPE_NAME", "dogstatsd_env");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("dd.trace.pipe.name", "trace_property");
        systemProperties.setProperty("dd.dogstatsd.pipe.name", "dogstatsd_property");

        NamedPipeNames names = NamedPipeNames.choose(env, systemProperties);

        assertEquals("trace_property", names.tracePipeName);
        assertEquals("dogstatsd_property", names.dogstatsdPipeName);
    }

    @Test
    void choose_generatesNamesWhenNoneAreSet() {
        NamedPipeNames names = NamedPipeNames.choose(new HashMap<>(), new Properties());

        assertTrue(names.tracePipeName.matches("dd_trace_[0-9a-f]{32}"), names.tracePipeName);
        assertTrue(names.dogstatsdPipeName.matches("dd_dogstatsd_[0-9a-f]{32}"), names.dogstatsdPipeName);
        assertTrue(names.warnings.isEmpty());
    }

    @Test
    void choose_generatesDifferentNamesEachTime() {
        NamedPipeNames first = NamedPipeNames.choose(new HashMap<>(), new Properties());
        NamedPipeNames second = NamedPipeNames.choose(new HashMap<>(), new Properties());

        assertNotEquals(first.tracePipeName, second.tracePipeName);
        assertNotEquals(first.dogstatsdPipeName, second.dogstatsdPipeName);
    }

    @Test
    void choose_treatsEmptyValuesAsUnset() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "");
        env.put("DD_TRACE_PIPE_NAME", "trace_env");
        env.put("DD_DOGSTATSD_PIPE_NAME", "");

        NamedPipeNames names = NamedPipeNames.choose(env, new Properties());

        assertEquals("trace_env", names.tracePipeName);
        assertTrue(names.dogstatsdPipeName.matches("dd_dogstatsd_[0-9a-f]{32}"), names.dogstatsdPipeName);
    }

    @Test
    void choose_warnsWhenCustomerSetNamesDifferFromWindowsPipeNames() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "apm_windows");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_windows");
        env.put("DD_TRACE_PIPE_NAME", "trace_env");
        Properties systemProperties = new Properties();
        systemProperties.setProperty("dd.dogstatsd.pipe.name", "dogstatsd_property");

        NamedPipeNames names = NamedPipeNames.choose(env, systemProperties);

        assertEquals(2, names.warnings.size(), names.warnings.toString());
        assertTrue(names.warnings.get(0).contains("DD_TRACE_PIPE_NAME"), names.warnings.get(0));
        assertTrue(names.warnings.get(0).contains("DD_APM_WINDOWS_PIPE_NAME"), names.warnings.get(0));
        assertTrue(names.warnings.get(1).contains("dd.dogstatsd.pipe.name"), names.warnings.get(1));
        assertTrue(names.warnings.get(1).contains("DD_DOGSTATSD_WINDOWS_PIPE_NAME"), names.warnings.get(1));
    }

    @Test
    void choose_doesNotWarnWhenCustomerSetNamesMatchWindowsPipeNames() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", "apm_windows");
        env.put("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dogstatsd_windows");
        env.put("DD_TRACE_PIPE_NAME", "apm_windows");
        env.put("DD_DOGSTATSD_PIPE_NAME", "dogstatsd_windows");

        NamedPipeNames names = NamedPipeNames.choose(env, new Properties());

        assertTrue(names.warnings.isEmpty(), names.warnings.toString());
    }

    @Test
    void choose_warnsWhenNamesAreLongerThan256Characters() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_APM_WINDOWS_PIPE_NAME", repeat('a', 257));
        Properties systemProperties = new Properties();
        systemProperties.setProperty("dd.dogstatsd.pipe.name", repeat('b', 300));

        NamedPipeNames names = NamedPipeNames.choose(env, systemProperties);

        assertEquals(2, names.warnings.size(), names.warnings.toString());
        assertTrue(names.warnings.get(0).contains("DD_APM_WINDOWS_PIPE_NAME"), names.warnings.get(0));
        assertTrue(names.warnings.get(0).contains("257"), names.warnings.get(0));
        assertTrue(names.warnings.get(1).contains("dd.dogstatsd.pipe.name"), names.warnings.get(1));
        assertTrue(names.warnings.get(1).contains("300"), names.warnings.get(1));
    }

    @Test
    void choose_doesNotWarnFor256CharacterNames() {
        Map<String, String> env = new HashMap<>();
        env.put("DD_TRACE_PIPE_NAME", repeat('a', 256));

        NamedPipeNames names = NamedPipeNames.choose(env, new Properties());

        assertTrue(names.warnings.isEmpty(), names.warnings.toString());
    }
}
