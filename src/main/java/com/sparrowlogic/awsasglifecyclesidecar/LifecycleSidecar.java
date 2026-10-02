package com.sparrowlogic.awsasglifecyclesidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CompleteLifecycleActionRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingInstancesRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeLifecycleHooksRequest;
import software.amazon.awssdk.services.autoscaling.model.RecordLifecycleActionHeartbeatRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Core sidecar logic: polls ASG state, manages termination protocol, and completes lifecycle actions.
 */
public class LifecycleSidecar {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleSidecar.class);
    private static final int STATUS_FORBIDDEN = 403;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final int COMPLETE_ACTION_MAX_RETRIES = 5;
    private static final int COMPLETE_ACTION_RETRY_DELAY_SECONDS = 5;
    private static final String TERMINATING_TRANSITION = "autoscaling:EC2_INSTANCE_TERMINATING";
    private static final String IAM_RESOURCE_ANY = "\"Resource\": \"*\"";
    private static final String TERMINATING_FILE = "terminating";
    private static final String TERMINATING_COMPLETE_FILE = "terminating-complete";

    private final SidecarConfig config;
    private final AutoScalingClient asgClient;
    private final Ec2Client ec2Client;

    /**
     * Constructor.
     *
     * @param config    sidecar configuration
     * @param asgClient AWS Auto Scaling client
     * @param ec2Client AWS EC2 client
     */
    public LifecycleSidecar(final SidecarConfig config, final AutoScalingClient asgClient,
                            final Ec2Client ec2Client) {
        this.config = config;
        this.asgClient = asgClient;
        this.ec2Client = ec2Client;
    }

    /**
     * Main run loop. Polls ASG state and manages the termination protocol.
     *
     * @param instanceId EC2 instance ID
     * @throws Exception on fatal error
     */
    public void run(final String instanceId) throws Exception {
        final Path dir = Path.of(this.config.lifecycleDir());
        final Path terminatingFile = dir.resolve(TERMINATING_FILE);
        final Path terminatingCompleteFile = dir.resolve(TERMINATING_COMPLETE_FILE);

        Files.createDirectories(dir);
        this.cleanFile(terminatingFile);
        this.cleanFile(terminatingCompleteFile);

        final String asgName = this.getAsgName(instanceId);
        if (asgName == null || asgName.isBlank() || "None".equals(asgName)) {
            LOG.error("FATAL: could not get ASG name for {}", instanceId);
            return;
        }

        final String hookName = this.resolveHookName(asgName);
        LOG.info("Started — instance={} asg={} hook={}", instanceId, asgName, hookName);
        LOG.info("Completion requires every signal present: {}", this.config.requiredSignals());
        LOG.info("Timeouts: max_termination_wait={}s heartbeat={}s poll={}s",
                this.config.maxTerminationWait(), this.config.heartbeatInterval(),
                this.config.pollInterval());

        this.pollUntilTerminating(instanceId, asgName, hookName,
                terminatingFile, terminatingCompleteFile);
    }

    private void pollUntilTerminating(final String instanceId, final String asgName,
                                      final String hookName, final Path terminatingFile,
                                      final Path terminatingCompleteFile) throws Exception {
        int checkCount = 0;
        while (!Thread.currentThread().isInterrupted()) {
            final String state = this.getLifecycleState(instanceId);

            if ("Terminating:Wait".equals(state)) {
                LOG.info("Terminating:Wait detected — writing terminating signal");
                this.writeSignalFile(terminatingFile);
                final boolean ready = this.waitForTerminatingComplete(terminatingCompleteFile,
                        instanceId, asgName, hookName, terminatingFile);
                if (ready) {
                    this.completeAction(instanceId, asgName, hookName);
                } else if (this.config.completeOnTimeout()) {
                    LOG.warn("Completing anyway after timeout (COMPLETE_ON_TIMEOUT=true). "
                            + "Set it false to leave an unfinished drain to the hook's own "
                            + "DefaultResult instead.");
                    this.completeAction(instanceId, asgName, hookName);
                } else {
                    LOG.warn("NOT completing the lifecycle action: not every required signal "
                            + "arrived and COMPLETE_ON_TIMEOUT=false. Leaving the hook to time "
                            + "out so AWS applies its DefaultResult, rather than this sidecar "
                            + "forcing CONTINUE over a drain that never finished.");
                }
                LOG.info("Done. Exiting.");
                return;
            }

            checkCount++;
            LOG.info("Monitoring (check={}, state={})", checkCount,
                    state != null ? state : "unknown");
            this.sleepSeconds(this.config.pollInterval());
        }
    }

    /**
     * Waits until every required signal file is present, sending heartbeats periodically.
     *
     * <p>Completion is gated on ALL of TERMINATION_SIGNALS, not just the first. A drain that
     * has several ordered steps -- quiesce sessions, then release a shared resource that must
     * not be released while sessions are live -- needs every step confirmed before the instance
     * is allowed to go away. Any one of them missing means the drain is unfinished.</p>
     *
     * @param terminatingCompleteFile path to the primary terminating-complete file
     * @param instanceId              EC2 instance ID
     * @param asgName                 ASG name
     * @param hookName                lifecycle hook name
     * @param terminatingFile         path to terminating file (re-asserted each cycle)
     * @return true when every required signal arrived, false if the wait timed out first
     * @throws InterruptedException if interrupted while sleeping
     */
    boolean waitForTerminatingComplete(final Path terminatingCompleteFile, final String instanceId,
                                       final String asgName, final String hookName,
                                       final Path terminatingFile)
            throws InterruptedException {
        final Path dir = terminatingCompleteFile.getParent();
        int elapsed = 0;
        int lastHeartbeat = 0;

        while (elapsed < this.config.maxTerminationWait()) {
            final var missing = this.missingSignals(dir);
            if (missing.isEmpty()) {
                LOG.info("All required signals received ({}) — app confirmed ready "
                        + "for termination", this.config.requiredSignals());
                return true;
            }
            LOG.info("Waiting on signals {} ({}s / {}s)", missing, elapsed,
                    this.config.maxTerminationWait());
            this.writeSignalFile(terminatingFile);
            if (elapsed - lastHeartbeat >= this.config.heartbeatInterval()) {
                this.sendHeartbeat(instanceId, asgName, hookName);
                lastHeartbeat = elapsed;
            }
            this.sleepSeconds(this.config.pollInterval());
            elapsed += this.config.pollInterval();
        }
        LOG.warn("Max termination wait reached with signals still missing: {}",
                this.missingSignals(dir));
        return false;
    }

    /**
     * Lists the required signal files that are not yet present.
     *
     * @param dir lifecycle directory
     * @return names of the signals still missing, in configured order
     */
    private List<String> missingSignals(final Path dir) {
        return this.config.requiredSignals().stream()
                .filter(name -> !Files.exists(dir.resolve(name)))
                .toList();
    }

    /**
     * Resolves the lifecycle hook name, preferring what AWS actually has over a guess.
     *
     * <p>Order: an explicit HOOK_NAME wins; otherwise the terminating hook is read back from
     * the ASG; otherwise a sanitised name derived from the ASG name is used. Deriving alone is
     * not safe -- an ASG name may contain characters AWS forbids in a lifecycleHookName (a dot
     * being the usual case), so the derived value can be a name AWS will reject with a 400
     * ValidationException. That is not retryable and not AccessDenied, so the symptom is a
     * drain that completes normally followed by an instance stuck in Terminating:Wait until
     * the hook times out.</p>
     *
     * @param asgName ASG name
     * @return hook name to use
     */
    String resolveHookName(final String asgName) {
        String resolved = this.config.hookName();
        if (resolved == null) {
            resolved = this.discoverHookName(asgName);
        }
        if (resolved == null) {
            final String derived = this.config.resolveHookName(asgName);
            final String raw = asgName + "-" + this.config.hookSuffix();
            if (!raw.equals(derived)) {
                LOG.warn("Derived hook name '{}' contains characters AWS forbids; using '{}'. "
                        + "Set HOOK_NAME explicitly if your hook is named differently.",
                        raw, derived);
            }
            resolved = derived;
        }
        return resolved;
    }

    /**
     * Reads the terminating lifecycle hook's real name back from the ASG.
     *
     * <p>Returns null when the lookup is not possible -- most often because the instance role
     * lacks autoscaling:DescribeLifecycleHooks, which is not fatal: the caller falls back to a
     * derived name.</p>
     *
     * @param asgName ASG name
     * @return hook name, or null if it could not be determined
     */
    String discoverHookName(final String asgName) {
        String result = null;
        try {
            final var resp = this.asgClient.describeLifecycleHooks(
                    DescribeLifecycleHooksRequest.builder()
                            .autoScalingGroupName(asgName).build());
            if (resp == null || resp.lifecycleHooks() == null) {
                LOG.warn("describeLifecycleHooks returned nothing for {} — "
                        + "falling back to a derived name", asgName);
            } else {
                result = this.pickHookName(asgName, resp.lifecycleHooks().stream()
                        .filter(h -> TERMINATING_TRANSITION.equals(h.lifecycleTransition()))
                        .map(h -> h.lifecycleHookName())
                        .filter(SidecarConfig::isValidHookName)
                        .toList());
            }
        } catch (final AwsServiceException e) {
            this.logAwsError("autoscaling:DescribeLifecycleHooks", e,
                    "autoscaling:DescribeLifecycleHooks", IAM_RESOURCE_ANY);
        } catch (final SdkClientException e) {
            LOG.warn("Could not describe lifecycle hooks: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Chooses among the terminating hooks found on the ASG.
     *
     * @param asgName ASG name
     * @param names   valid terminating hook names
     * @return the hook to use, or null if there were none
     */
    private String pickHookName(final String asgName, final List<String> names) {
        String picked = null;
        if (names.isEmpty()) {
            LOG.warn("No {} hook found on {} — falling back to a derived name",
                    TERMINATING_TRANSITION, asgName);
        } else if (names.size() > 1) {
            // Prefer one matching what we would have derived, so a multi-hook ASG stays
            // predictable rather than depending on API ordering.
            final String derived = this.config.resolveHookName(asgName);
            picked = names.contains(derived) ? derived : names.getFirst();
            LOG.warn("ASG {} has {} terminating hooks {} — using '{}'",
                    asgName, names.size(), names, picked);
        } else {
            picked = names.getFirst();
            LOG.info("Discovered lifecycle hook '{}' on {}", picked, asgName);
        }
        return picked;
    }


    /**
     * Queries the lifecycle state of the given instance.
     *
     * @param instanceId EC2 instance ID
     * @return lifecycle state string, or null on error
     */
    String getLifecycleState(final String instanceId) {
        try {
            final var resp = this.asgClient.describeAutoScalingInstances(
                    DescribeAutoScalingInstancesRequest.builder().instanceIds(instanceId).build());
            final var instances = resp.autoScalingInstances();
            return instances.isEmpty() ? null : instances.getFirst().lifecycleState();
        } catch (final AwsServiceException e) {
            this.logAwsError("autoscaling:DescribeAutoScalingInstances", e,
                    "autoscaling:DescribeAutoScalingInstances", IAM_RESOURCE_ANY);
        } catch (final SdkClientException e) {
            LOG.warn("Failed to get lifecycle state: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Looks up the ASG name from EC2 instance tags.
     *
     * @param instanceId EC2 instance ID
     * @return ASG name, or null on error
     */
    String getAsgName(final String instanceId) {
        try {
            final var resp = this.ec2Client.describeTags(DescribeTagsRequest.builder()
                    .filters(
                            Filter.builder().name("resource-id").values(instanceId).build(),
                            Filter.builder().name("key").values("aws:autoscaling:groupName").build()
                    ).build());
            final var tags = resp.tags();
            return tags.isEmpty() ? null : tags.getFirst().value();
        } catch (final AwsServiceException e) {
            this.logAwsError("ec2:DescribeTags", e,
                    "ec2:DescribeTags", IAM_RESOURCE_ANY);
        } catch (final SdkClientException e) {
            LOG.warn("Failed to get ASG name: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Sends a lifecycle heartbeat to keep the hook alive.
     *
     * @param instanceId EC2 instance ID
     * @param asgName    ASG name
     * @param hookName   lifecycle hook name
     */
    void sendHeartbeat(final String instanceId, final String asgName, final String hookName) {
        try {
            LOG.info("Sending lifecycle heartbeat");
            this.asgClient.recordLifecycleActionHeartbeat(
                    RecordLifecycleActionHeartbeatRequest.builder()
                            .lifecycleHookName(hookName)
                            .autoScalingGroupName(asgName)
                            .instanceId(instanceId).build());
        } catch (final AwsServiceException e) {
            this.logAwsError("autoscaling:RecordLifecycleActionHeartbeat", e,
                    "autoscaling:RecordLifecycleActionHeartbeat",
                    "\"Resource\": \"arn:aws:autoscaling:*:*:autoScalingGroup:*"
                            + ":autoScalingGroupName/" + asgName + "\"");
        } catch (final SdkClientException e) {
            LOG.warn("Heartbeat failed: {}", e.getMessage());
        }
    }

    /**
     * Completes the lifecycle action with CONTINUE, retrying on transient failures.
     *
     * @param instanceId EC2 instance ID
     * @param asgName    ASG name
     * @param hookName   lifecycle hook name
     */
    void completeAction(final String instanceId, final String asgName, final String hookName) {
        boolean completed = false;
        for (int attempt = 1; attempt <= COMPLETE_ACTION_MAX_RETRIES && !completed; attempt++) {
            try {
                LOG.info("Completing lifecycle action — CONTINUE (attempt {}/{})",
                        attempt, COMPLETE_ACTION_MAX_RETRIES);
                this.asgClient.completeLifecycleAction(
                        CompleteLifecycleActionRequest.builder()
                                .lifecycleHookName(hookName)
                                .autoScalingGroupName(asgName)
                                .instanceId(instanceId)
                                .lifecycleActionResult("CONTINUE").build());
                LOG.info("Lifecycle action completed successfully");
                completed = true;
            } catch (final AwsServiceException e) {
                if (isAccessDenied(e)) {
                    this.logAwsError("autoscaling:CompleteLifecycleAction", e,
                            "autoscaling:CompleteLifecycleAction",
                            "\"Resource\": \"arn:aws:autoscaling:*:*:autoScalingGroup:*"
                                    + ":autoScalingGroupName/" + asgName + "\"");
                    completed = true;
                } else {
                    this.logRetryableError("completeLifecycleAction", attempt, e.getMessage());
                }
            } catch (final SdkClientException e) {
                this.logRetryableError("completeLifecycleAction", attempt, e.getMessage());
            }
            if (!completed) {
                this.retrySleep(attempt);
            }
        }
        if (!completed) {
            LOG.error("completeLifecycleAction failed after {} retries — "
                    + "instance will wait for hook timeout", COMPLETE_ACTION_MAX_RETRIES);
        }
    }

    private void logRetryableError(final String operation, final int attempt, final String message) {
        if (attempt < COMPLETE_ACTION_MAX_RETRIES) {
            LOG.warn("{} attempt {}/{} failed: {} — retrying in {}s",
                    operation, attempt, COMPLETE_ACTION_MAX_RETRIES, message,
                    COMPLETE_ACTION_RETRY_DELAY_SECONDS);
        } else {
            LOG.error("{} attempt {}/{} failed: {}", operation, attempt,
                    COMPLETE_ACTION_MAX_RETRIES, message);
        }
    }

    private void retrySleep(final int attempt) {
        if (attempt < COMPLETE_ACTION_MAX_RETRIES) {
            try {
                this.sleepSeconds(COMPLETE_ACTION_RETRY_DELAY_SECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void logAwsError(final String operation, final AwsServiceException e,
                             final String iamAction, final String iamResource) {
        if (isAccessDenied(e)) {
            LOG.error("{} failed: ACCESS DENIED. "
                    + "The EC2 instance role is missing the required IAM permission.", operation);
            LOG.error("Add the following to your IAM policy:");
            LOG.error("  {{");
            LOG.error("    \"Effect\": \"Allow\",");
            LOG.error("    \"Action\": \"{}\",", iamAction);
            LOG.error("    {}", iamResource);
            LOG.error("  }}");
            LOG.error("See README.md § Minimum IAM Policy for the full policy document.");
        } else {
            final var details = e.awsErrorDetails();
            final String msg = details != null ? details.errorMessage() : e.getMessage();
            LOG.warn("{} failed: {} (HTTP {})", operation, msg, e.statusCode());
        }
    }

    private static boolean isAccessDenied(final AwsServiceException e) {
        if (e.statusCode() == STATUS_FORBIDDEN) {
            return true;
        }
        final var details = e.awsErrorDetails();
        final String code = details != null ? details.errorCode() : "";
        return "AccessDenied".equals(code)
                || "UnauthorizedAccess".equals(code)
                || "AccessDeniedException".equals(code);
    }

    /**
     * Writes a signal file idempotently. Logs a warning if it cannot be written.
     *
     * @param file path to signal file
     */
    private void writeSignalFile(final Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createFile(file);
                LOG.info("Wrote signal file: {}", file);
            }
        } catch (final IOException e) {
            LOG.warn("Could not write signal file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Removes a file, logging a warning on failure.
     *
     * @param file path to remove
     */
    private void cleanFile(final Path file) {
        try {
            if (Files.deleteIfExists(file)) {
                LOG.info("Cleaned up stale file: {}", file);
            }
        } catch (final IOException e) {
            LOG.warn("Could not remove {}: {}", file, e.getMessage());
        }
    }

    /**
     * Sleeps for the given number of seconds. Overridden in tests.
     *
     * @param seconds seconds to sleep
     * @throws InterruptedException if interrupted
     */
    void sleepSeconds(final int seconds) throws InterruptedException {
        Thread.sleep(seconds * MILLIS_PER_SECOND);
    }
}
