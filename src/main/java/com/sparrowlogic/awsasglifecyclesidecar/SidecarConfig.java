package com.sparrowlogic.awsasglifecyclesidecar;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Configuration for the lifecycle sidecar, loaded from environment variables.
 */
public record SidecarConfig(
        String lifecycleDir,
        int pollInterval,
        int heartbeatInterval,
        int maxTerminationWait,
        String hookName,
        String hookSuffix,
        List<String> requiredSignals,
        boolean completeOnTimeout
) {
    /** The signal file an app writes to confirm it is ready for termination. */
    static final String DEFAULT_SIGNAL = "terminating-complete";
    /** Default poll interval in seconds. */
    private static final int DEFAULT_POLL_INTERVAL = 30;
    /** Default heartbeat interval in seconds. */
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 60;
    /** Default max termination wait in seconds (115 minutes). */
    private static final int DEFAULT_MAX_TERMINATION_WAIT = 6900;

    /**
     * AWS constrains lifecycleHookName to this character set. An ASG name may legally contain
     * characters outside it -- a dot is the common case -- so a hook name derived from the ASG
     * name is not necessarily a valid hook name.
     */
    private static final String HOOK_NAME_ILLEGAL_CHARS = "[^A-Za-z0-9\\-_/]";

    /**
     * Canonical constructor. Defaults requiredSignals when unset so a config built by hand
     * still gates on the standard signal rather than on nothing at all -- an empty list would
     * mean "complete immediately", which is the opposite of safe.
     *
     * @param lifecycleDir       shared signal directory
     * @param pollInterval       seconds between ASG state checks
     * @param heartbeatInterval  seconds between lifecycle heartbeats
     * @param maxTerminationWait max seconds to wait for the signals
     * @param hookName           explicit hook name override, or null
     * @param hookSuffix         suffix appended to the ASG name when deriving a hook name
     * @param requiredSignals    every signal that must be present before completing
     * @param completeOnTimeout  whether to complete anyway when the wait times out
     */
    public SidecarConfig {
        if (requiredSignals == null || requiredSignals.isEmpty()) {
            requiredSignals = List.of(DEFAULT_SIGNAL);
        } else {
            requiredSignals = List.copyOf(requiredSignals);
        }
    }

    /**
     * Convenience constructor gating on the default signal only.
     *
     * @param lifecycleDir       shared signal directory
     * @param pollInterval       seconds between ASG state checks
     * @param heartbeatInterval  seconds between lifecycle heartbeats
     * @param maxTerminationWait max seconds to wait for the signal
     * @param hookName           explicit hook name override, or null
     * @param hookSuffix         suffix appended to the ASG name when deriving a hook name
     */
    SidecarConfig(final String lifecycleDir, final int pollInterval,
                  final int heartbeatInterval, final int maxTerminationWait,
                  final String hookName, final String hookSuffix) {
        this(lifecycleDir, pollInterval, heartbeatInterval, maxTerminationWait,
                hookName, hookSuffix, List.of(DEFAULT_SIGNAL), true);
    }

    /**
     * Creates config from system environment variables.
     *
     * @return config
     */
    static SidecarConfig fromEnv() {
        return fromEnv(System.getenv()::get);
    }

    /**
     * Creates config from a custom env lookup function (testable).
     *
     * @param env function to look up env vars
     * @return config
     */
    static SidecarConfig fromEnv(final Function<String, String> env) {
        return new SidecarConfig(
                envOr(env, "LIFECYCLE_DIR", "/lifecycle"),
                intEnvOr(env, "POLL_INTERVAL", DEFAULT_POLL_INTERVAL),
                intEnvOr(env, "HEARTBEAT_INTERVAL", DEFAULT_HEARTBEAT_INTERVAL),
                intEnvOr(env, "MAX_TERMINATION_WAIT", DEFAULT_MAX_TERMINATION_WAIT),
                blankToNull(env.apply("HOOK_NAME")),
                envOr(env, "HOOK_SUFFIX", "terminate-wait"),
                signalsOr(env, "TERMINATION_SIGNALS", DEFAULT_SIGNAL),
                boolEnvOr(env, "COMPLETE_ON_TIMEOUT", true)
        );
    }

    /**
     * Resolves the lifecycle hook name to use when no name could be discovered from AWS.
     *
     * <p>An explicit HOOK_NAME is taken verbatim -- the operator stated it, so it is not
     * second-guessed. A derived name is sanitised, because concatenating the ASG name can
     * produce something AWS rejects outright.</p>
     *
     * @param asgName ASG name
     * @return hook name
     */
    String resolveHookName(final String asgName) {
        if (this.hookName != null) {
            return this.hookName;
        }
        return sanitizeHookName(asgName + "-" + this.hookSuffix);
    }

    /**
     * Replaces every character AWS disallows in a lifecycleHookName with a hyphen.
     *
     * <p>Mirrors the sanitisation an operator has to apply when creating the hook, since AWS
     * rejects the raw name at creation time too. Terraform users typically write
     * replace(asg.name, ".", "-").</p>
     *
     * @param name candidate hook name
     * @return name containing only characters AWS accepts
     */
    static String sanitizeHookName(final String name) {
        return name == null ? null : name.replaceAll(HOOK_NAME_ILLEGAL_CHARS, "-");
    }

    /**
     * True when the given name is already acceptable to the AWS API.
     *
     * @param name candidate hook name
     * @return whether AWS would accept the name
     */
    static boolean isValidHookName(final String name) {
        return name != null && !name.isBlank() && name.equals(sanitizeHookName(name));
    }

    private static String blankToNull(final String v) {
        return v != null && !v.isBlank() ? v : null;
    }

    private static List<String> signalsOr(final Function<String, String> env,
                                          final String key, final String def) {
        return Arrays.stream(envOr(env, key, def).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String envOr(final Function<String, String> env,
                                final String key, final String def) {
        final String v = env.apply(key);
        return v != null && !v.isBlank() ? v : def;
    }

    private static boolean boolEnvOr(final Function<String, String> env,
                                     final String key, final boolean def) {
        final String v = env.apply(key);
        return v != null && !v.isBlank() ? Boolean.parseBoolean(v) : def;
    }

    private static int intEnvOr(final Function<String, String> env,
                                final String key, final int def) {
        final String v = env.apply(key);
        return v != null && !v.isBlank() ? Integer.parseInt(v) : def;
    }
}
