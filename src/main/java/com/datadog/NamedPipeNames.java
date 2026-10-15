package com.datadog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Chooses the trace and DogStatsD pipe names for one function, following the .NET
 * compatibility layer: the binary's *_WINDOWS_PIPE_NAME setting, then a name the customer
 * already set for the tracer, then a generated per-function name.
 */
final class NamedPipeNames {
    static final int MAX_PIPE_NAME_LENGTH = 256;

    static final String TRACE_WINDOWS_PIPE_NAME_ENV = "DD_APM_WINDOWS_PIPE_NAME";
    static final String TRACE_PIPE_NAME_ENV = "DD_TRACE_PIPE_NAME";
    static final String TRACE_PIPE_NAME_PROPERTY = "dd.trace.pipe.name";

    static final String DOGSTATSD_WINDOWS_PIPE_NAME_ENV = "DD_DOGSTATSD_WINDOWS_PIPE_NAME";
    static final String DOGSTATSD_PIPE_NAME_ENV = "DD_DOGSTATSD_PIPE_NAME";
    static final String DOGSTATSD_PIPE_NAME_PROPERTY = "dd.dogstatsd.pipe.name";

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
        String tracePipeName = choosePipeName(env, systemProperties, TRACE_WINDOWS_PIPE_NAME_ENV,
                TRACE_PIPE_NAME_PROPERTY, TRACE_PIPE_NAME_ENV, "dd_trace_", warnings);
        String dogstatsdPipeName = choosePipeName(env, systemProperties, DOGSTATSD_WINDOWS_PIPE_NAME_ENV,
                DOGSTATSD_PIPE_NAME_PROPERTY, DOGSTATSD_PIPE_NAME_ENV, "dd_dogstatsd_", warnings);
        return new NamedPipeNames(tracePipeName, dogstatsdPipeName, warnings);
    }

    private static String choosePipeName(Map<String, String> env, Properties systemProperties,
            String windowsPipeNameEnv, String pipeNameProperty, String pipeNameEnv,
            String generatedPrefix, List<String> warnings) {
        // The tracer reads system properties before environment variables, so a JVM option
        // the customer passed is the customer-set name when both are present.
        String customerSetName = nonEmpty(systemProperties.getProperty(pipeNameProperty));
        String customerSetSource = pipeNameProperty;
        if (customerSetName == null) {
            customerSetName = nonEmpty(env.get(pipeNameEnv));
            customerSetSource = pipeNameEnv;
        }

        String windowsPipeName = nonEmpty(env.get(windowsPipeNameEnv));
        if (windowsPipeName != null) {
            if (customerSetName != null && !customerSetName.equals(windowsPipeName)) {
                warnings.add(String.format("%s ('%s') differs from %s ('%s'). Using %s.",
                        customerSetSource, customerSetName, windowsPipeNameEnv, windowsPipeName,
                        windowsPipeNameEnv));
            }
            warnIfTooLong(windowsPipeNameEnv, windowsPipeName, warnings);
            return windowsPipeName;
        }

        if (customerSetName != null) {
            warnIfTooLong(customerSetSource, customerSetName, warnings);
            return customerSetName;
        }

        return generatedPrefix + UUID.randomUUID().toString().replace("-", "");
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
