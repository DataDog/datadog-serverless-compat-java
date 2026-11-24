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

    // First dd-trace-java release with the named-pipe fixes (DataDog/dd-trace-java#12898).
    private static final int[] MINIMUM_VERSION = {1, 69, 0};
    static final String MINIMUM_TRACER_VERSION =
            MINIMUM_VERSION[0] + "." + MINIMUM_VERSION[1] + "." + MINIMUM_VERSION[2];

    private static final String JAVAAGENT_PREFIX = "-javaagent:";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(\\D.*)?$");

    final boolean passed;
    final String reason;
    // The JVM runs -javaagent premains in command-line order, so a tracer listed first reads its
    // settings before this agent sets the pipe system properties.
    final boolean tracerListedBeforeCompat;

    TracerVersionGate(boolean passed, String reason, boolean tracerListedBeforeCompat) {
        this.passed = passed;
        this.reason = reason;
        this.tracerListedBeforeCompat = tracerListedBeforeCompat;
    }

    static TracerVersionGate failed(String reason) {
        return new TracerVersionGate(false, reason, false);
    }

    static TracerVersionGate check() {
        List<String> jvmArguments;
        try {
            jvmArguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Exception e) {
            return failed("the JVM arguments could not be read to find dd-java-agent: " + e);
        }
        return check(jvmArguments);
    }

    static TracerVersionGate check(List<String> jvmArguments) {
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
            return failed("no readable dd-java-agent is among the -javaagent options (Premain-Class "
                    + TRACER_PREMAIN_CLASS + ")");
        }

        String version = tracerManifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        if (version == null || version.isEmpty()) {
            return failed("dd-java-agent at " + jarPath + " has no Implementation-Version in its manifest");
        }
        int[] tracer = parseVersion(version);
        if (tracer == null) {
            return failed("dd-java-agent at " + jarPath + " has an unreadable version '" + version + "'");
        }
        if (compare(tracer, MINIMUM_VERSION) < 0) {
            return failed("dd-java-agent " + version + " at " + jarPath + " is older than "
                    + MINIMUM_TRACER_VERSION + ", the first version that supports named pipes");
        }
        return new TracerVersionGate(true, "dd-java-agent " + version + " at " + jarPath
                + " supports named pipes (minimum " + MINIMUM_TRACER_VERSION + ")", tracerListedBeforeCompat);
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

    // Reads major.minor.patch and ignores any suffix, so 1.69.0-SNAPSHOT counts as 1.69.0.
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
