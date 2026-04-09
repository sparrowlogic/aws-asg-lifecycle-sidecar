package com.sparrowlogic.awsasglifecyclesidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.imds.Ec2MetadataClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * Entry point for the ASG lifecycle sidecar.
 */
public final class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private App() { }

    /**
     * Main method.
     *
     * @param args command line arguments
     * @throws Exception on fatal error
     */
    public static void main(final String[] args) throws Exception {
        LOG.info("Initializing asg-lifecycle-sidecar...");

        final SidecarConfig config = SidecarConfig.fromEnv();
        LOG.info("Config: lifecycleDir={} poll={}s heartbeat={}s maxTerminationWait={}s",
                config.lifecycleDir(), config.pollInterval(),
                config.heartbeatInterval(), config.maxTerminationWait());

        LOG.info("Contacting EC2 IMDS at 169.254.169.254 (this will time out if not on EC2)...");
        final Ec2MetadataClient imds = Ec2MetadataClient.create();

        LOG.info("Fetching instance ID from IMDS...");
        final String instanceId = imds.get("/latest/meta-data/instance-id").asString().trim();
        LOG.info("Instance ID: {}", instanceId);

        LOG.info("Resolving AWS region...");
        final Region region = resolveRegion(imds);
        LOG.info("Region: {}", region);

        LOG.info("Creating AWS clients...");
        final AutoScalingClient asgClient = AutoScalingClient.builder().region(region).build();
        final Ec2Client ec2Client = Ec2Client.builder().region(region).build();

        new LifecycleSidecar(config, asgClient, ec2Client).run(instanceId);
    }

    private static Region resolveRegion(final Ec2MetadataClient imds) {
        String envRegion = System.getenv("AWS_REGION");
        if (envRegion == null || envRegion.isBlank()) {
            envRegion = System.getenv("AWS_DEFAULT_REGION");
        }
        if (envRegion != null && !envRegion.isBlank()) {
            LOG.info("Using region from environment: {}", envRegion);
            return Region.of(envRegion);
        }
        LOG.info("No AWS_REGION set, detecting from IMDS availability zone...");
        final String az = imds.get("/latest/meta-data/placement/availability-zone").asString().trim();
        return Region.of(az.replaceAll("[a-z]$", ""));
    }
}
