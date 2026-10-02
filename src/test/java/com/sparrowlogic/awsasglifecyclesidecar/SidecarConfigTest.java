package com.sparrowlogic.awsasglifecyclesidecar;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SidecarConfigTest {

    @Test
    void fromEnv_usesDefaults_whenNoVarsSet() {
        final var config = SidecarConfig.fromEnv(key -> null);

        assertEquals("/lifecycle", config.lifecycleDir());
        assertEquals(30, config.pollInterval());
        assertEquals(60, config.heartbeatInterval());
        assertEquals(6900, config.maxTerminationWait());
        assertNull(config.hookName());
        assertEquals("terminate-wait", config.hookSuffix());
    }

    @Test
    void fromEnv_usesDefaults_whenVarsBlank() {
        final var config = SidecarConfig.fromEnv(key -> "  ");

        assertEquals("/lifecycle", config.lifecycleDir());
        assertEquals(30, config.pollInterval());
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

    @Test
    void resolveHookName_sanitizesDotsFromAsgName() {
        // AWS allows dots in an ASG name but forbids them in lifecycleHookName, so a derived
        // name must be sanitised or CompleteLifecycleAction fails with a 400 ValidationError.
        final var config = new SidecarConfig("/lc", 1, 1, 1, null, "terminate-wait");
        assertEquals("com-example-svc-production-terminate-wait",
                config.resolveHookName("com.example.svc-production"));
    }

    @Test
    void resolveHookName_doesNotSanitizeExplicitOverride() {
        final var config = new SidecarConfig("/lc", 1, 1, 1, "literal.name", "terminate-wait");
        assertEquals("literal.name", config.resolveHookName("whatever"));
    }

    @Test
    void sanitizeHookName_replacesEveryIllegalCharacter() {
        assertEquals("a-b-c-d_e/f", SidecarConfig.sanitizeHookName("a.b c:d_e/f"));
        assertNull(SidecarConfig.sanitizeHookName(null));
    }

    @Test
    void isValidHookName_matchesAwsConstraint() {
        assertTrue(SidecarConfig.isValidHookName("plain-name_1/x"));
        assertFalse(SidecarConfig.isValidHookName("has.dot"));
        assertFalse(SidecarConfig.isValidHookName(""));
        assertFalse(SidecarConfig.isValidHookName(null));
    }

    @Test
    void fromEnv_blankHookName_isTreatedAsUnset() {
        final Map<String, String> env = new HashMap<>();
        env.put("HOOK_NAME", "   ");
        assertNull(SidecarConfig.fromEnv(env::get).hookName());
    }

    @Test
    void fromEnv_parsesTerminationSignals() {
        final Map<String, String> env = new HashMap<>();
        env.put("TERMINATION_SIGNALS", " terminating-complete , anycast-released ,, ");
        assertEquals(List.of("terminating-complete", "anycast-released"),
                SidecarConfig.fromEnv(env::get).requiredSignals());
    }

    @Test
    void requiredSignals_defaultsToStandardSignal() {
        assertEquals(List.of("terminating-complete"),
                SidecarConfig.fromEnv(key -> null).requiredSignals());
        // An explicitly empty list would mean "complete immediately" -- never honour that.
        final var blank = new SidecarConfig("/lc", 1, 1, 1, null, "s", List.of(), true);
        assertEquals(List.of("terminating-complete"), blank.requiredSignals());
    }

    @Test
    void fromEnv_completeOnTimeout_defaultsTrueAndIsOverridable() {
        assertTrue(SidecarConfig.fromEnv(key -> null).completeOnTimeout());
        final Map<String, String> env = new HashMap<>();
        env.put("COMPLETE_ON_TIMEOUT", "false");
        assertFalse(SidecarConfig.fromEnv(env::get).completeOnTimeout());
    }
}
