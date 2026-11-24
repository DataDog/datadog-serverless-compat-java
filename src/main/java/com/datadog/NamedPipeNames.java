package com.datadog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** The settings that name one of a function's two pipes. */
enum NamedPipe {
    TRACE("DD_APM_WINDOWS_PIPE_NAME", "dd.trace.pipe.name", "DD_TRACE_PIPE_NAME", "dd_trace_"),
    DOGSTATSD("DD_DOGSTATSD_WINDOWS_PIPE_NAME", "dd.dogstatsd.pipe.name", "DD_DOGSTATSD_PIPE_NAME",
            "dd_dogstatsd_");

    /** The binary's setting. */
    final String windowsPipeNameEnv;
    /** The tracer's JVM option, which it reads before {@link #tracerEnv}. */
    final String tracerProperty;
    final String tracerEnv;
    final String generatedPrefix;

    NamedPipe(String windowsPipeNameEnv, String tracerProperty, String tracerEnv, String generatedPrefix) {
        this.windowsPipeNameEnv = windowsPipeNameEnv;
        this.tracerProperty = tracerProperty;
        this.tracerEnv = tracerEnv;
        this.generatedPrefix = generatedPrefix;
    }
}

/**
 * Chooses the trace and DogStatsD pipe names for one function: the binary's
 * *_WINDOWS_PIPE_NAME setting, then a name the customer already set for the tracer, then a
 * generated per-function name.
 */
final class NamedPipeNames {
    static final int MAX_PIPE_NAME_LENGTH = 256;

    final String tracePipeName;
    final String dogstatsdPipeName;
    final List<String> warnings;

    private NamedPipeNames(String tracePipeName, String dogstatsdPipeName, List<String> warnings) {
        this.tracePipeName = tracePipeName;
        this.dogstatsdPipeName = dogstatsdPipeName;
        this.warnings = Collections.unmodifiableList(warnings);
    }

    /**
     * @param tracerGetsPipes whether dd-java-agent will be given the chosen names, which only
     *     changes the wording of the warnings
     */
    static NamedPipeNames choose(Map<String, String> env, Properties systemProperties, boolean tracerGetsPipes) {
        List<String> warnings = new ArrayList<>();
        String tracePipeName = choosePipeName(NamedPipe.TRACE, env, systemProperties, tracerGetsPipes, warnings);
        String dogstatsdPipeName =
                choosePipeName(NamedPipe.DOGSTATSD, env, systemProperties, tracerGetsPipes, warnings);
        return new NamedPipeNames(tracePipeName, dogstatsdPipeName, warnings);
    }

    private static String choosePipeName(NamedPipe pipe, Map<String, String> env, Properties systemProperties,
            boolean tracerGetsPipes, List<String> warnings) {
        // The tracer reads system properties before environment variables, so a JVM option
        // the customer passed is the customer-set name when both are present.
        String customerSetName = nonEmpty(systemProperties.getProperty(pipe.tracerProperty));
        String customerSetSource = pipe.tracerProperty;
        if (customerSetName == null) {
            customerSetName = nonEmpty(env.get(pipe.tracerEnv));
            customerSetSource = pipe.tracerEnv;
        }

        String windowsPipeName = nonEmpty(env.get(pipe.windowsPipeNameEnv));

        // The binary's *_WINDOWS_PIPE_NAME setting wins over the customer-set tracer name, so
        // warn when the customer set two different names. When dd-java-agent gets the chosen
        // name, its own setting is replaced. Otherwise it keeps its own setting, and the two
        // sides use different pipes.
        if (windowsPipeName != null && customerSetName != null && !customerSetName.equals(windowsPipeName)) {
            String outcome = tracerGetsPipes
                    ? String.format("Both the binary and dd-java-agent use %s.", pipe.windowsPipeNameEnv)
                    : String.format("The binary uses %s, and dd-java-agent keeps %s.", pipe.windowsPipeNameEnv,
                            customerSetSource);
            warnings.add(String.format("%s ('%s') differs from %s ('%s'). %s", customerSetSource,
                    customerSetName, pipe.windowsPipeNameEnv, windowsPipeName, outcome));
        }

        // Use the first name that is set: the binary's setting, then the customer-set tracer name.
        String pipeName = windowsPipeName;
        String source = pipe.windowsPipeNameEnv;
        if (pipeName == null) {
            pipeName = customerSetName;
            source = customerSetSource;
        }

        // With neither set, generate a name that is unique to this function. Generated names
        // are always short enough, so only a name the customer set needs the length check.
        if (pipeName == null) {
            return pipe.generatedPrefix + UUID.randomUUID().toString().replace("-", "");
        }
        warnIfTooLong(source, pipeName, warnings);
        return pipeName;
    }

    private static void warnIfTooLong(String source, String pipeName, List<String> warnings) {
        if (pipeName.length() > MAX_PIPE_NAME_LENGTH) {
            warnings.add(String.format(
                    "%s is %d characters long. Windows pipe names can be at most %d characters.",
                    source, pipeName.length(), MAX_PIPE_NAME_LENGTH));
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
