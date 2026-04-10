package com.sparrowlogic.awsasglifecyclesidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.CompleteLifecycleActionRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingInstancesRequest;
import software.amazon.awssdk.services.autoscaling.model.RecordLifecycleActionHeartbeatRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeTagsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Core sidecar logic: polls ASG state, manages termination protocol, and completes lifecycle actions.
 */
public class LifecycleSidecar {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleSidecar.class);
    private static final int STATUS_FORBIDDEN = 403;
    private static final long MILLIS_PER_SECOND = 1000L;
    private static final int COMPLETE_ACTION_MAX_RETRIES = 5;
    private static final int COMPLETE_ACTION_RETRY_DELAY_SECONDS = 5;
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

        final String hookName = this.config.resolveHookName(asgName);
        LOG.info("Started — instance={} asg={} hook={}", instanceId, asgName, hookName);
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
                this.waitForTerminatingComplete(terminatingCompleteFile, instanceId, asgName,
                        hookName, terminatingFile);
                this.completeAction(instanceId, asgName, hookName);
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
     * Waits for the terminating-complete file, sending heartbeats periodically.
     *
     * @param terminatingCompleteFile path to terminating-complete file
     * @param instanceId              EC2 instance ID
     * @param asgName                 ASG name
     * @param hookName                lifecycle hook name
     * @param terminatingFile         path to terminating file (re-asserted each cycle)
     * @throws InterruptedException if interrupted while sleeping
     */
    void waitForTerminatingComplete(final Path terminatingCompleteFile, final String instanceId,
                                    final String asgName, final String hookName,
                                    final Path terminatingFile)
            throws InterruptedException {
        int elapsed = 0;
        int lastHeartbeat = 0;

        while (elapsed < this.config.maxTerminationWait()) {
            if (Files.exists(terminatingCompleteFile)) {
                LOG.info("terminating-complete received — app confirmed ready for termination");
                return;
            }
            LOG.info("Checking for terminating-complete ({}s / {}s) — not yet present",
                    elapsed, this.config.maxTerminationWait());
            this.writeSignalFile(terminatingFile);
            if (elapsed - lastHeartbeat >= this.config.heartbeatInterval()) {
                this.sendHeartbeat(instanceId, asgName, hookName);
                lastHeartbeat = elapsed;
            }
            this.sleepSeconds(this.config.pollInterval());
            elapsed += this.config.pollInterval();
        }
        LOG.warn("Max termination wait reached — proceeding with termination");
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
                    "autoscaling:DescribeAutoScalingInstances", "\"Resource\": \"*\"");
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
                    "ec2:DescribeTags", "\"Resource\": \"*\"");
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
