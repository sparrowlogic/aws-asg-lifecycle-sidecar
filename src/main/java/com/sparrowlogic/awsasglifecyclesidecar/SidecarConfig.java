package com.sparrowlogic.awsasglifecyclesidecar;

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
        String hookSuffix
) {
    /** Default poll interval in seconds. */
    private static final int DEFAULT_POLL_INTERVAL = 10;
    /** Default heartbeat interval in seconds. */
    private static final int DEFAULT_HEARTBEAT_INTERVAL = 60;
    /** Default max termination wait in seconds (115 minutes). */
    private static final int DEFAULT_MAX_TERMINATION_WAIT = 6900;

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
                env.apply("HOOK_NAME"),
                envOr(env, "HOOK_SUFFIX", "terminate-wait")
        );
    }

    /**
     * Resolves the lifecycle hook name.
     *
     * @param asgName ASG name
     * @return hook name
     */
    String resolveHookName(final String asgName) {
        return this.hookName != null ? this.hookName : asgName + "-" + this.hookSuffix;
    }

    private static String envOr(final Function<String, String> env,
                                final String key, final String def) {
        final String v = env.apply(key);
        return v != null && !v.isBlank() ? v : def;
    }

    private static int intEnvOr(final Function<String, String> env,
                                final String key, final int def) {
        final String v = env.apply(key);
        return v != null && !v.isBlank() ? Integer.parseInt(v) : def;
    }
}
