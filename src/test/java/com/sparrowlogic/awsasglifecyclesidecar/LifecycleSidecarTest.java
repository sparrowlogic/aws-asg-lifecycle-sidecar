package com.sparrowlogic.awsasglifecyclesidecar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Timeout(5)
class LifecycleSidecarTest {

    private static final String INSTANCE_ID = "i-1234567890abcdef0";
    private static final String ASG_NAME = "my-asg";
    private static final String HOOK_NAME = "my-asg-terminate-wait";

    @Mock AutoScalingClient asgClient;
    @Mock Ec2Client ec2Client;
    @TempDir Path tempDir;

    private LifecycleSidecar sidecar;

    @BeforeEach
    void setUp() {
        var config = new SidecarConfig(tempDir.toString(), 1, 1, 3, null, "terminate-wait");
        sidecar = new LifecycleSidecar(config, asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) { /* no-op */ }
        };
    }

    private void stubAsgName() {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeTagsResponse.builder()
                        .tags(software.amazon.awssdk.services.ec2.model.TagDescription.builder()
                                .value(ASG_NAME).build())
                        .build());
    }

    private DescribeAutoScalingInstancesResponse describeResponse(String state) {
        return DescribeAutoScalingInstancesResponse.builder()
                .autoScalingInstances(AutoScalingInstanceDetails.builder()
                        .lifecycleState(state).build())
                .build();
    }

    private DescribeAutoScalingInstancesResponse emptyDescribeResponse() {
        return DescribeAutoScalingInstancesResponse.builder()
                .autoScalingInstances(List.of())
                .build();
    }

    private AwsServiceException accessDeniedException(int statusCode, String errorCode) {
        return AwsServiceException.builder()
                .statusCode(statusCode)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode(errorCode)
                        .errorMessage("User is not authorized")
                        .serviceName("test")
                        .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder()
                                .statusCode(statusCode).build())
                        .build())
                .build();
    }

    private AwsServiceException nonAccessDeniedAwsException() {
        return AwsServiceException.builder()
                .statusCode(500)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("InternalError")
                        .errorMessage("Something went wrong")
                        .serviceName("test")
                        .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder()
                                .statusCode(500).build())
                        .build())
                .build();
    }

    // --- Full lifecycle ---

    @Test
    void fullLifecycle_detectsTerminatingWait_writesSignal_completesAction() throws Exception {
        stubAsgName();
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("InService"))
                .thenReturn(describeResponse("Terminating:Wait"));

        var signalCreated = new CountDownLatch(1);
        var appThread = Thread.ofVirtual().start(() -> {
            try {
                signalCreated.await();
                Files.createFile(tempDir.resolve("terminating-complete"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        sidecar = new LifecycleSidecar(
                new SidecarConfig(tempDir.toString(), 1, 1, 3, null, "terminate-wait"),
                asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) {
                if (Files.exists(tempDir.resolve("terminating"))) {
                    signalCreated.countDown();
                }
            }
        };

        sidecar.run(INSTANCE_ID);
        appThread.join();

        assertTrue(Files.exists(tempDir.resolve("terminating")));
        verify(asgClient).completeLifecycleAction(any(CompleteLifecycleActionRequest.class));
    }

    // --- run() early exits ---

    @Test
    void run_exitsEarly_whenAsgNameNull() throws Exception {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeTagsResponse.builder()
                        .tags(List.of()).build());

        sidecar.run(INSTANCE_ID);

        verify(asgClient, never()).describeAutoScalingInstances(
                any(DescribeAutoScalingInstancesRequest.class));
    }

    @Test
    void run_exitsEarly_whenAsgNameIsNone() throws Exception {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeTagsResponse.builder()
                        .tags(software.amazon.awssdk.services.ec2.model.TagDescription.builder()
                                .value("None").build())
                        .build());

        sidecar.run(INSTANCE_ID);

        verify(asgClient, never()).describeAutoScalingInstances(
                any(DescribeAutoScalingInstancesRequest.class));
    }

    @Test
    void run_exitsEarly_whenAsgNameIsBlank() throws Exception {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeTagsResponse.builder()
                        .tags(software.amazon.awssdk.services.ec2.model.TagDescription.builder()
                                .value("  ").build())
                        .build());

        sidecar.run(INSTANCE_ID);

        verify(asgClient, never()).describeAutoScalingInstances(
                any(DescribeAutoScalingInstancesRequest.class));
    }

    @Test
    void run_survivesPreExistingTerminatingFile() throws Exception {
        stubAsgName();
        Files.createFile(tempDir.resolve("terminating"));
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("Terminating:Wait"));

        Files.createFile(tempDir.resolve("terminating-complete"));
        sidecar.run(INSTANCE_ID);

        verify(asgClient).completeLifecycleAction(any(CompleteLifecycleActionRequest.class));
    }


    // --- hook name resolution ---

    private LifecycleSidecar sidecarWith(SidecarConfig cfg) {
        return new LifecycleSidecar(cfg, asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) { /* no-op */ }
        };
    }

    private void stubHooks(LifecycleHook... hooks) {
        when(asgClient.describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class)))
                .thenReturn(DescribeLifecycleHooksResponse.builder().lifecycleHooks(hooks).build());
    }

    /** Idempotent signal-file creation usable from sleepSeconds (no checked IOException). */
    private static void touchQuietly(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static LifecycleHook hook(String name, String transition) {
        return LifecycleHook.builder().lifecycleHookName(name)
                .lifecycleTransition(transition).build();
    }

    @Test
    void discoverHookName_returnsRealNameFromAws() {
        stubHooks(hook("com-example-prod-terminate-wait", "autoscaling:EC2_INSTANCE_TERMINATING"));
        assertEquals("com-example-prod-terminate-wait", sidecar.discoverHookName("com.example.prod"));
    }

    @Test
    void discoverHookName_ignoresLaunchingHooks() {
        stubHooks(hook("launch-hook", "autoscaling:EC2_INSTANCE_LAUNCHING"));
        assertNull(sidecar.discoverHookName(ASG_NAME));
    }

    @Test
    void discoverHookName_returnsNull_onNullResponse() {
        when(asgClient.describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class)))
                .thenReturn(null);
        assertNull(sidecar.discoverHookName(ASG_NAME));
    }

    @Test
    void discoverHookName_returnsNull_whenAccessDenied() {
        when(asgClient.describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class)))
                .thenThrow(AwsServiceException.builder().statusCode(403)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
                        .build());
        assertNull(sidecar.discoverHookName(ASG_NAME));
    }

    @Test
    void discoverHookName_returnsNull_onSdkClientException() {
        when(asgClient.describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class)))
                .thenThrow(SdkClientException.builder().message("boom").build());
        assertNull(sidecar.discoverHookName(ASG_NAME));
    }

    @Test
    void discoverHookName_prefersDerivedMatch_whenSeveralTerminatingHooks() {
        stubHooks(hook("other-hook", "autoscaling:EC2_INSTANCE_TERMINATING"),
                  hook(HOOK_NAME, "autoscaling:EC2_INSTANCE_TERMINATING"));
        assertEquals(HOOK_NAME, sidecar.discoverHookName(ASG_NAME));
    }

    @Test
    void resolveHookName_prefersExplicitOverride_withoutCallingAws() {
        var cfg = new SidecarConfig(tempDir.toString(), 1, 1, 3, "explicit-hook", "terminate-wait");
        assertEquals("explicit-hook", sidecarWith(cfg).resolveHookName("com.example.prod"));
        verify(asgClient, never()).describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class));
    }

    @Test
    void resolveHookName_fallsBackToSanitizedName_whenDiscoveryUnavailable() {
        when(asgClient.describeLifecycleHooks(any(DescribeLifecycleHooksRequest.class)))
                .thenReturn(DescribeLifecycleHooksResponse.builder().build());
        // The pre-fix behaviour sent "com.example.prod-terminate-wait", which AWS rejects.
        assertEquals("com-example-prod-terminate-wait", sidecar.resolveHookName("com.example.prod"));
    }

    // --- completion gating ---

    @Test
    void completion_waitsForEverySignal_notJustTheFirst() throws Exception {
        var cfg = new SidecarConfig(tempDir.toString(), 1, 1, 3, HOOK_NAME, "terminate-wait",
                List.of("terminating-complete", "anycast-released"), false);
        stubAsgName();
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("Terminating:Wait"));

        // Only the first signal ever arrives.
        var sc = new LifecycleSidecar(cfg, asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) {
                touchQuietly(tempDir.resolve("terminating-complete"));
            }
        };
        sc.run(INSTANCE_ID);

        verify(asgClient, never()).completeLifecycleAction(any(CompleteLifecycleActionRequest.class));
    }

    @Test
    void completion_fires_whenEverySignalPresent() throws Exception {
        var cfg = new SidecarConfig(tempDir.toString(), 1, 1, 3, HOOK_NAME, "terminate-wait",
                List.of("terminating-complete", "anycast-released"), false);
        stubAsgName();
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("Terminating:Wait"));

        var sc = new LifecycleSidecar(cfg, asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) {
                touchQuietly(tempDir.resolve("terminating-complete"));
                touchQuietly(tempDir.resolve("anycast-released"));
            }
        };
        sc.run(INSTANCE_ID);

        verify(asgClient).completeLifecycleAction(any(CompleteLifecycleActionRequest.class));
    }

    @Test
    void completion_skipped_onTimeout_whenCompleteOnTimeoutFalse() throws Exception {
        var cfg = new SidecarConfig(tempDir.toString(), 1, 1, 2, HOOK_NAME, "terminate-wait",
                List.of("terminating-complete"), false);
        stubAsgName();
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("Terminating:Wait"));

        sidecarWith(cfg).run(INSTANCE_ID);

        verify(asgClient, never()).completeLifecycleAction(any(CompleteLifecycleActionRequest.class));
    }

    // --- getLifecycleState ---

    @Test
    void getLifecycleState_returnsState() {
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(describeResponse("InService"));
        assertEquals("InService", sidecar.getLifecycleState(INSTANCE_ID));
    }

    @Test
    void getLifecycleState_returnsNull_whenEmpty() {
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenReturn(emptyDescribeResponse());
        assertNull(sidecar.getLifecycleState(INSTANCE_ID));
    }

    @Test
    void getLifecycleState_returnsNull_onSdkClientException() {
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenThrow(SdkClientException.create("timeout"));
        assertNull(sidecar.getLifecycleState(INSTANCE_ID));
    }

    @Test
    void getLifecycleState_returnsNull_onAccessDenied() {
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenThrow(accessDeniedException(403, "AccessDenied"));
        assertNull(sidecar.getLifecycleState(INSTANCE_ID));
    }

    @Test
    void getLifecycleState_returnsNull_onNonAccessDeniedAwsError() {
        when(asgClient.describeAutoScalingInstances(any(DescribeAutoScalingInstancesRequest.class)))
                .thenThrow(nonAccessDeniedAwsException());
        assertNull(sidecar.getLifecycleState(INSTANCE_ID));
    }

    // --- getAsgName ---

    @Test
    void getAsgName_returnsTagValue() {
        stubAsgName();
        assertEquals(ASG_NAME, sidecar.getAsgName(INSTANCE_ID));
    }

    @Test
    void getAsgName_returnsNull_whenNoTags() {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenReturn(software.amazon.awssdk.services.ec2.model.DescribeTagsResponse.builder()
                        .tags(List.of()).build());
        assertNull(sidecar.getAsgName(INSTANCE_ID));
    }

    @Test
    void getAsgName_returnsNull_onAccessDenied() {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenThrow(accessDeniedException(403, "AccessDenied"));
        assertNull(sidecar.getAsgName(INSTANCE_ID));
    }

    @Test
    void getAsgName_returnsNull_onSdkClientException() {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenThrow(SdkClientException.create("timeout"));
        assertNull(sidecar.getAsgName(INSTANCE_ID));
    }

    @Test
    void getAsgName_returnsNull_onNonAccessDeniedAwsError() {
        when(ec2Client.describeTags(any(software.amazon.awssdk.services.ec2.model.DescribeTagsRequest.class)))
                .thenThrow(nonAccessDeniedAwsException());
        assertNull(sidecar.getAsgName(INSTANCE_ID));
    }

    // --- sendHeartbeat ---

    @Test
    void sendHeartbeat_sendsCorrectRequest() {
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient).recordLifecycleActionHeartbeat(
                RecordLifecycleActionHeartbeatRequest.builder()
                        .lifecycleHookName(HOOK_NAME)
                        .autoScalingGroupName(ASG_NAME)
                        .instanceId(INSTANCE_ID).build());
    }

    @Test
    void sendHeartbeat_handlesAccessDenied() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(accessDeniedException(403, "AccessDeniedException"));
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    @Test
    void sendHeartbeat_handlesSdkClientException() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(SdkClientException.create("timeout"));
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    @Test
    void sendHeartbeat_handlesNonAccessDeniedAwsError() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(nonAccessDeniedAwsException());
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    // --- completeAction with retries ---

    @Test
    void completeAction_sendsCorrectRequest() {
        sidecar.completeAction(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient).completeLifecycleAction(
                CompleteLifecycleActionRequest.builder()
                        .lifecycleHookName(HOOK_NAME)
                        .autoScalingGroupName(ASG_NAME)
                        .instanceId(INSTANCE_ID)
                        .lifecycleActionResult("CONTINUE").build());
    }

    @Test
    void completeAction_retriesOnTransientFailure_thenSucceeds() {
        when(asgClient.completeLifecycleAction(any(CompleteLifecycleActionRequest.class)))
                .thenThrow(SdkClientException.create("connection reset"))
                .thenThrow(nonAccessDeniedAwsException())
                .thenReturn(CompleteLifecycleActionResponse.builder().build());

        sidecar.completeAction(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient, times(3)).completeLifecycleAction(
                any(CompleteLifecycleActionRequest.class));
    }

    @Test
    void completeAction_givesUp_afterMaxRetries() {
        when(asgClient.completeLifecycleAction(any(CompleteLifecycleActionRequest.class)))
                .thenThrow(SdkClientException.create("timeout"));

        sidecar.completeAction(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient, times(5)).completeLifecycleAction(
                any(CompleteLifecycleActionRequest.class));
    }

    @Test
    void completeAction_doesNotRetry_onAccessDenied() {
        when(asgClient.completeLifecycleAction(any(CompleteLifecycleActionRequest.class)))
                .thenThrow(accessDeniedException(403, "UnauthorizedAccess"));

        sidecar.completeAction(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient, times(1)).completeLifecycleAction(
                any(CompleteLifecycleActionRequest.class));
    }

    @Test
    void completeAction_handlesNonAccessDeniedAwsError_withRetry() {
        when(asgClient.completeLifecycleAction(any(CompleteLifecycleActionRequest.class)))
                .thenThrow(nonAccessDeniedAwsException())
                .thenReturn(CompleteLifecycleActionResponse.builder().build());

        sidecar.completeAction(INSTANCE_ID, ASG_NAME, HOOK_NAME);

        verify(asgClient, times(2)).completeLifecycleAction(
                any(CompleteLifecycleActionRequest.class));
    }

    // --- waitForTerminatingComplete ---

    @Test
    void waitForTerminatingComplete_timesOut_whenNoFile() throws Exception {
        sidecar.waitForTerminatingComplete(
                tempDir.resolve("terminating-complete"), INSTANCE_ID, ASG_NAME, HOOK_NAME,
                tempDir.resolve("terminating"));
    }

    @Test
    void waitForTerminatingComplete_returnsImmediately_whenFileExists() throws Exception {
        Files.createFile(tempDir.resolve("terminating-complete"));
        sidecar.waitForTerminatingComplete(
                tempDir.resolve("terminating-complete"), INSTANCE_ID, ASG_NAME, HOOK_NAME,
                tempDir.resolve("terminating"));
        verify(asgClient, never()).recordLifecycleActionHeartbeat(
                any(RecordLifecycleActionHeartbeatRequest.class));
    }

    @Test
    void waitForTerminatingComplete_rewritesSignalFile_ifRemoved() throws Exception {
        final Path terminatingFile = tempDir.resolve("terminating");
        final Path terminatingComplete = tempDir.resolve("terminating-complete");
        Files.createFile(terminatingFile);

        var config = new SidecarConfig(tempDir.toString(), 1, 1, 3, null, "terminate-wait");
        final var counter = new AtomicInteger();
        sidecar = new LifecycleSidecar(config, asgClient, ec2Client) {
            @Override void sleepSeconds(int seconds) throws InterruptedException {
                if (counter.incrementAndGet() == 1) {
                    try { Files.deleteIfExists(terminatingFile); } catch (Exception ignored) { }
                } else if (counter.get() == 2) {
                    try { Files.createFile(terminatingComplete); } catch (Exception ignored) { }
                }
            }
        };

        sidecar.waitForTerminatingComplete(terminatingComplete, INSTANCE_ID, ASG_NAME, HOOK_NAME,
                terminatingFile);
        assertTrue(Files.exists(terminatingFile), "terminating file should be re-created after removal");
    }

    // --- isAccessDenied branches ---

    @Test
    void accessDenied_detectedByStatusCode403() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(accessDeniedException(403, "SomeOtherCode"));
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    @Test
    void accessDenied_detectedByErrorCode_nonForbiddenStatus() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(accessDeniedException(400, "AccessDenied"));
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    @Test
    void accessDenied_detectedByAccessDeniedException_errorCode() {
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(accessDeniedException(400, "AccessDeniedException"));
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    @Test
    void accessDenied_nullDetails_notAccessDenied() {
        var ex = AwsServiceException.builder()
                .statusCode(400)
                .build();
        when(asgClient.recordLifecycleActionHeartbeat(any(RecordLifecycleActionHeartbeatRequest.class)))
                .thenThrow(ex);
        sidecar.sendHeartbeat(INSTANCE_ID, ASG_NAME, HOOK_NAME);
    }

    // --- SidecarConfig ---

    @Test
    void hookName_usesOverride_whenSet() {
        var config = new SidecarConfig(tempDir.toString(), 1, 1, 3, "custom-hook", "terminate-wait");
        assertEquals("custom-hook", config.resolveHookName("any-asg"));
    }

    @Test
    void hookName_usesAsgNameAndSuffix_whenNotSet() {
        var config = new SidecarConfig(tempDir.toString(), 1, 1, 3, null, "terminate-wait");
        assertEquals("my-asg-terminate-wait", config.resolveHookName("my-asg"));
    }

    @Test
    void config_recordAccessors() {
        var config = new SidecarConfig("/lc", 5, 30, 100, "hook", "suffix");
        assertEquals("/lc", config.lifecycleDir());
        assertEquals(5, config.pollInterval());
        assertEquals(30, config.heartbeatInterval());
        assertEquals(100, config.maxTerminationWait());
        assertEquals("hook", config.hookName());
        assertEquals("suffix", config.hookSuffix());
    }
}
