package com.datadog;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether the dd-java-agent in this JVM supports named pipes. Older tracers hang
 * uploading traces over a pipe and never send their own metrics over one, so pipe names are
 * only handed out when the tracer is at least {@link #MINIMUM_TRACER_VERSION}.
 */
final class TracerVersionGate {
    static final String TRACER_PREMAIN_CLASS = "datadog.trace.bootstrap.AgentPreCheck";
    static final String COMPAT_PREMAIN_CLASS = ServerlessCompatAgent.class.getName();

    // Placeholder: the next unreleased dd-trace-java version on master. Replace it with the
    // first dd-trace-java release that includes the named-pipe transport and StatsD fixes.
    static final String MINIMUM_TRACER_VERSION = "1.68.0";

    private static final String JAVAAGENT_PREFIX = "-javaagent:";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(\\D.*)?$");

    final boolean passed;
    final String reason;
    // The JVM runs -javaagent premains in command-line order, so a tracer listed first reads its
    // settings before this agent sets the pipe system properties.
    final boolean tracerListedBeforeCompat;

    private TracerVersionGate(boolean passed, String reason, boolean tracerListedBeforeCompat) {
        this.passed = passed;
        this.reason = reason;
        this.tracerListedBeforeCompat = tracerListedBeforeCompat;
    }

    static TracerVersionGate check() {
        List<String> jvmArguments;
        try {
            jvmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Exception e) {
            return new TracerVersionGate(false, "unable to read JVM arguments to find dd-java-agent: " + e, false);
        }
        return check(jvmArguments);
    }

    static TracerVersionGate check(List<String> jvmArguments) {
        return check(jvmArguments, MINIMUM_TRACER_VERSION);
    }

    static TracerVersionGate check(List<String> jvmArguments, String minimumVersion) {
        int[] minimum = parseVersion(minimumVersion);
        if (minimum == null) {
            return new TracerVersionGate(false, "invalid minimum dd-java-agent version " + minimumVersion, false);
        }

        String jarPath = null;
        Manifest tracerManifest = null;
        boolean tracerListedBeforeCompat = false;
        for (String argument : jvmArguments) {
            if (!argument.startsWith(JAVAAGENT_PREFIX)) {
                continue;
            }
            String agentJarPath = javaagentJarPath(argument);
            Manifest manifest = readManifest(agentJarPath);
            if (manifest == null) {
                continue;
            }
            String premainClass = manifest.getMainAttributes().getValue("Premain-Class");
            if (COMPAT_PREMAIN_CLASS.equals(premainClass) && tracerManifest != null) {
                tracerListedBeforeCompat = true;
            } else if (TRACER_PREMAIN_CLASS.equals(premainClass) && tracerManifest == null) {
                jarPath = agentJarPath;
                tracerManifest = manifest;
            }
        }

        if (tracerManifest == null) {
            return new TracerVersionGate(false,
                    "no readable dd-java-agent found among -javaagent options (Premain-Class " + TRACER_PREMAIN_CLASS + ")",
                    false);
        }

        String version = tracerManifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (version == null || version.isEmpty()) {
            return new TracerVersionGate(false,
                    "dd-java-agent at " + jarPath + " has no Implementation-Version in its manifest",
                    tracerListedBeforeCompat);
        }
        int[] tracer = parseVersion(version);
        if (tracer == null) {
            return new TracerVersionGate(false,
                    "unable to read dd-java-agent version '" + version + "' at " + jarPath, tracerListedBeforeCompat);
        }
        if (compare(tracer, minimum) < 0) {
            return new TracerVersionGate(false, "dd-java-agent " + version + " at " + jarPath
                    + " is older than " + minimumVersion + ", the first version that supports named pipes",
                    tracerListedBeforeCompat);
        }
        return new TracerVersionGate(true, "dd-java-agent " + version + " at " + jarPath
                + " supports named pipes (minimum " + minimumVersion + ")", tracerListedBeforeCompat);
    }

    // The JVM treats everything after the first '=' as agent options.
    private static String javaagentJarPath(String argument) {
        String value = argument.substring(JAVAAGENT_PREFIX.length());
        int optionsStart = value.indexOf('=');
        return optionsStart >= 0 ? value.substring(0, optionsStart) : value;
    }

    private static Manifest readManifest(String jarPath) {
        try (JarFile jarFile = new JarFile(jarPath, false)) {
            return jarFile.getManifest();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Reads major.minor.patch and ignores any suffix, so 1.68.0-SNAPSHOT counts as 1.68.0.
    private static int[] parseVersion(String version) {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }
}
