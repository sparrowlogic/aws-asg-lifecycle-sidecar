package com.sparrowlogic.awsasglifecyclesidecar;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SidecarConfigTest {

    @Test
    void fromEnv_usesDefaults_whenNoVarsSet() {
        final var config = SidecarConfig.fromEnv(key -> null);

        assertEquals("/lifecycle", config.lifecycleDir());
        assertEquals(10, config.pollInterval());
        assertEquals(60, config.heartbeatInterval());
        assertEquals(6900, config.maxTerminationWait());
        assertNull(config.hookName());
        assertEquals("terminate-wait", config.hookSuffix());
    }

    @Test
    void fromEnv_usesDefaults_whenVarsBlank() {
        final var config = SidecarConfig.fromEnv(key -> "  ");

        assertEquals("/lifecycle", config.lifecycleDir());
        assertEquals(10, config.pollInterval());
        assertEquals(60, config.heartbeatInterval());
        assertEquals(6900, config.maxTerminationWait());
        assertEquals("terminate-wait", config.hookSuffix());
    }

    @Test
    void fromEnv_readsAllVars() {
        final Map<String, String> env = new HashMap<>();
        env.put("LIFECYCLE_DIR", "/custom");
        env.put("POLL_INTERVAL", "5");
        env.put("HEARTBEAT_INTERVAL", "30");
        env.put("MAX_TERMINATION_WAIT", "120");
        env.put("HOOK_NAME", "my-hook");
        env.put("HOOK_SUFFIX", "custom-suffix");

        final var config = SidecarConfig.fromEnv(env::get);

        assertEquals("/custom", config.lifecycleDir());
        assertEquals(5, config.pollInterval());
        assertEquals(30, config.heartbeatInterval());
        assertEquals(120, config.maxTerminationWait());
        assertEquals("my-hook", config.hookName());
        assertEquals("custom-suffix", config.hookSuffix());
    }

    @Test
    void resolveHookName_usesOverride() {
        final var config = new SidecarConfig("/lc", 1, 1, 1, "override", "suffix");
        assertEquals("override", config.resolveHookName("my-asg"));
    }

    @Test
    void resolveHookName_fallsBackToSuffix() {
        final var config = new SidecarConfig("/lc", 1, 1, 1, null, "terminate-wait");
        assertEquals("my-asg-terminate-wait", config.resolveHookName("my-asg"));
    }
}
