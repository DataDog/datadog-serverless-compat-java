package com.datadog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TracerVersionGateTest {
    private static final String MINIMUM = "1.68.0";
    private static final String TRACER_PREMAIN_CLASS = "datadog.trace.bootstrap.AgentPreCheck";

    @TempDir
    Path tempDir;

    private Path writeAgentJar(String fileName, String premainClass, String implementationVersion)
            throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (premainClass != null) {
            attributes.putValue("Premain-Class", premainClass);
        }
        if (implementationVersion != null) {
            attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
        }
        Path jar = tempDir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            // A manifest is all the gate reads.
        }
        return jar;
    }

    private Path writeTracerJar(String implementationVersion) throws IOException {
        return writeAgentJar("dd-java-agent.jar", TRACER_PREMAIN_CLASS, implementationVersion);
    }

    private static String javaagent(Path jar) {
        return "-javaagent:" + jar.toAbsolutePath();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.68.0", "1.68.1", "1.69.0", "1.100.0", "2.0.0"})
    void check_passesForMinimumVersionOrLater(String version) throws IOException {
        Path tracer = writeTracerJar(version);

        TracerVersionGate gate = TracerVersionGate.check(
                Collections.singletonList(javaagent(tracer)), MINIMUM);

        assertTrue(gate.passed, gate.reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.67.1", "1.67.0", "1.9.9", "0.99.0", "1.67.0-SNAPSHOT"})
    void check_failsForOlderVersions(String version) throws IOException {
        Path tracer = writeTracerJar(version);

        TracerVersionGate gate = TracerVersionGate.check(
                Collections.singletonList(javaagent(tracer)), MINIMUM);

        assertFalse(gate.passed);
        assertTrue(gate.reason.contains(version), gate.reason);
        assertTrue(gate.reason.contains(MINIMUM), gate.reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.68.0-SNAPSHOT", "1.68.0-105-gefa6bd64", "1.68.0-SNAPSHOT-DIRTY"})
    void check_passesForDevBuildsOfMinimumVersion(String version) throws IOException {
        Path tracer = writeTracerJar(version);

        TracerVersionGate gate = TracerVersionGate.check(
                Collections.singletonList(javaagent(tracer)), MINIMUM);

        assertTrue(gate.passed, gate.reason);
    }

    @Test
    void check_usesDefaultMinimumVersion() throws IOException {
        Path tracer = writeTracerJar(TracerVersionGate.MINIMUM_TRACER_VERSION);

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(tracer)));

        assertTrue(gate.passed, gate.reason);
    }

    @Test
    void check_findsTracerByPremainClassNotFileName() throws IOException {
        Path compat = writeAgentJar("dd-java-agent-lookalike.jar", "com.datadog.ServerlessCompatAgent", "9.9.9");
        Path tracer = writeAgentJar("renamed-tracer.jar", TRACER_PREMAIN_CLASS, "1.68.0");

        TracerVersionGate gate = TracerVersionGate.check(
                Arrays.asList("-Xmx512m", javaagent(compat), javaagent(tracer)), MINIMUM);

        assertTrue(gate.passed, gate.reason);
    }

    @Test
    void check_ignoresAgentOptions() throws IOException {
        Path tracer = writeTracerJar("1.68.0");

        TracerVersionGate gate = TracerVersionGate.check(
                Collections.singletonList(javaagent(tracer) + "=some=option"), MINIMUM);

        assertTrue(gate.passed, gate.reason);
    }

    @Test
    void check_failsWhenNoJavaagentIsPresent() {
        TracerVersionGate gate = TracerVersionGate.check(Arrays.asList("-Xmx512m", "-Dfoo=bar"), MINIMUM);

        assertFalse(gate.passed);
        assertTrue(gate.reason.contains("dd-java-agent"), gate.reason);
    }

    @Test
    void check_failsWhenOnlyOtherAgentsArePresent() throws IOException {
        Path compat = writeAgentJar("dd-java-agent.jar", "com.datadog.ServerlessCompatAgent", "1.68.0");

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(compat)), MINIMUM);

        assertFalse(gate.passed);
    }

    @Test
    void check_failsWhenTracerJarDoesNotExist() {
        Path missing = tempDir.resolve("dd-java-agent.jar");

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(missing)), MINIMUM);

        assertFalse(gate.passed);
    }

    @Test
    void check_failsWhenJavaagentIsNotAJar() throws IOException {
        Path notAJar = tempDir.resolve("dd-java-agent.jar");
        Files.write(notAJar, "not a jar".getBytes("UTF-8"));

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(notAJar)), MINIMUM);

        assertFalse(gate.passed);
    }

    @Test
    void check_failsWhenTracerVersionIsMissing() throws IOException {
        Path tracer = writeTracerJar(null);

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(tracer)), MINIMUM);

        assertFalse(gate.passed);
        assertTrue(gate.reason.contains("Implementation-Version"), gate.reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "1.68", "v1.68.0", ""})
    void check_failsWhenTracerVersionIsUnreadable(String version) throws IOException {
        Path tracer = writeTracerJar(version);

        TracerVersionGate gate = TracerVersionGate.check(Collections.singletonList(javaagent(tracer)), MINIMUM);

        assertFalse(gate.passed);
    }
}
