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
 * Chooses the trace and DogStatsD pipe names for one function, following the .NET
 * compatibility layer: the binary's *_WINDOWS_PIPE_NAME setting, then a name the customer
 * already set for the tracer, then a generated per-function name.
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

    static NamedPipeNames choose(Map<String, String> env, Properties systemProperties) {
        List<String> warnings = new ArrayList<>();
        String tracePipeName = choosePipeName(NamedPipe.TRACE, env, systemProperties, warnings);
        String dogstatsdPipeName = choosePipeName(NamedPipe.DOGSTATSD, env, systemProperties, warnings);
        return new NamedPipeNames(tracePipeName, dogstatsdPipeName, warnings);
    }

    private static String choosePipeName(NamedPipe pipe, Map<String, String> env, Properties systemProperties,
            List<String> warnings) {
        // The tracer reads system properties before environment variables, so a JVM option
        // the customer passed is the customer-set name when both are present.
        String customerSetName = nonEmpty(systemProperties.getProperty(pipe.tracerProperty));
        String customerSetSource = pipe.tracerProperty;
        if (customerSetName == null) {
            customerSetName = nonEmpty(env.get(pipe.tracerEnv));
            customerSetSource = pipe.tracerEnv;
        }

        String windowsPipeName = nonEmpty(env.get(pipe.windowsPipeNameEnv));
        if (windowsPipeName != null) {
            if (customerSetName != null && !customerSetName.equals(windowsPipeName)) {
                warnings.add(String.format("%s ('%s') differs from %s ('%s'). The binary uses %s.",
                        customerSetSource, customerSetName, pipe.windowsPipeNameEnv, windowsPipeName,
                        pipe.windowsPipeNameEnv));
            }
            warnIfTooLong(pipe.windowsPipeNameEnv, windowsPipeName, warnings);
            return windowsPipeName;
        }

        if (customerSetName != null) {
            warnIfTooLong(customerSetSource, customerSetName, warnings);
            return customerSetName;
        }

        return pipe.generatedPrefix + UUID.randomUUID().toString().replace("-", "");
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
