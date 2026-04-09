# asg-lifecycle-sidecar

[![Build & Publish](https://github.com/sparrowlogic/aws-asg-lifecycle-sidecar/actions/workflows/build.yml/badge.svg)](https://github.com/sparrowlogic/aws-asg-lifecycle-sidecar/actions/workflows/build.yml)
[![GitHub release](https://img.shields.io/github/v/release/sparrowlogic/aws-asg-lifecycle-sidecar)](https://github.com/sparrowlogic/aws-asg-lifecycle-sidecar/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![GHCR](https://img.shields.io/badge/GHCR-sparrowlogic%2Faws--asg--lifecycle--sidecar-blue?logo=github)](https://ghcr.io/sparrowlogic/aws-asg-lifecycle-sidecar)

A Docker sidecar that coordinates graceful AWS Auto Scaling termination with any containerized service via a shared-volume file protocol.

Your app doesn't need AWS credentials, SDKs, or any awareness of Auto Scaling — it just watches for a file and writes one back.

## Protocol

```
┌──────────────────┐              ┌──────────────────┐
│ asg-lifecycle    │              │ your app         │
│ sidecar          │              │                  │
│                  │  /lifecycle  │                  │
│ polls ASG state  │    volume    │                  │
│                  │              │                  │
│ writes           │              │                  │
│  terminating ────┼─────────────▶│ detects          │
│                  │              │  terminating     │
│                  │              │ finishes work    │
│ reads            │              │                  │
│  terminating- ◀──┼──────────────┤ writes           │
│  complete        │              │  terminating-    │
│                  │              │  complete        │
│ calls AWS:       │              │                  │
│  CONTINUE        │              │ (no AWS needed)  │
└──────────────────┘              └──────────────────┘
```

1. ASG puts instance in `Terminating:Wait`
2. Sidecar detects it, writes `/lifecycle/terminating`
3. Your app sees the file, finishes in-flight work, writes `/lifecycle/terminating-complete`
4. Sidecar sees `terminating-complete`, calls `complete-lifecycle-action CONTINUE`
5. ASG proceeds with termination

## Quick Start

```yaml
# docker-compose.yml
services:
  your-app:
    image: your-app:latest
    volumes:
      - lifecycle:/lifecycle

  asg-lifecycle:
    image: ghcr.io/jackdpeterson/asg-lifecycle-sidecar:latest
    network_mode: host  # simplest option for EC2 IMDS access
    volumes:
      - lifecycle:/lifecycle
    environment:
      - AWS_REGION=us-west-1

volumes:
  lifecycle:
```

## Your App's Side

Watch for the terminating file and signal back when ready. Minimal example:

```bash
# In your app's entrypoint or health loop
if [ -f /lifecycle/terminating ]; then
    # finish in-flight work, close connections, etc.
    touch /lifecycle/terminating-complete
fi
```

Or with inotifywait:

```bash
inotifywait -e create /lifecycle/ --include 'terminating$'
# finish work...
touch /lifecycle/terminating-complete
```

## Networking & IMDS Hop Limit

The sidecar needs access to the EC2 Instance Metadata Service (IMDS) at `169.254.169.254` to discover its instance ID, region, and ASG name.

**`network_mode: host`** (recommended) — the container shares the host's network stack and IMDS is reachable with the default hop limit of 1. No extra configuration needed.

**Bridge networking** — if you can't use host networking, the container sits behind an extra network hop. IMDSv2 defaults to a hop limit of 1, so the metadata token request will fail. You must increase `HttpPutResponseHopLimit` to 2 on the instance:

```bash
aws ec2 modify-instance-metadata-options \
    --instance-id i-1234567890abcdef0 \
    --http-put-response-hop-limit 2 \
    --http-endpoint enabled
```

Or in Terraform on the launch template:

```hcl
resource "aws_launch_template" "example" {
  # ...
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"  # enforce IMDSv2
    http_put_response_hop_limit = 2
  }
}
```

> **Why 2?** Each Docker bridge network adds one hop. The default limit of 1 only allows the host itself to reach IMDS. Setting it to 2 allows containers one bridge hop away to reach it.

## Prerequisites

- An ASG lifecycle hook on `autoscaling:EC2_INSTANCE_TERMINATING`
- IAM permissions on the EC2 instance role (see [Minimum IAM Policy](#minimum-iam-policy) below)

### Terraform Example — Lifecycle Hook

```hcl
resource "aws_autoscaling_lifecycle_hook" "terminate_wait" {
  name                   = "${aws_autoscaling_group.asg.name}-terminate-wait"
  autoscaling_group_name = aws_autoscaling_group.asg.name
  lifecycle_transition   = "autoscaling:EC2_INSTANCE_TERMINATING"
  heartbeat_timeout      = 7200  # 2 hours max
  default_result         = "CONTINUE"
}
```

### Minimum IAM Policy

Attach this to the EC2 instance role. Scope the ASG resource ARN to your specific group.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "LifecycleActions",
      "Effect": "Allow",
      "Action": [
        "autoscaling:CompleteLifecycleAction",
        "autoscaling:RecordLifecycleActionHeartbeat"
      ],
      "Resource": "arn:aws:autoscaling:*:*:autoScalingGroup:*:autoScalingGroupName/YOUR-ASG-NAME"
    },
    {
      "Sid": "DescribeInstances",
      "Effect": "Allow",
      "Action": [
        "autoscaling:DescribeAutoScalingInstances"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ReadInstanceTags",
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeTags"
      ],
      "Resource": "*"
    }
  ]
}
```

### Terraform Example — IAM Policy

```hcl
resource "aws_iam_policy" "asg_lifecycle" {
  name = "asg-lifecycle-sidecar"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "LifecycleActions"
        Effect   = "Allow"
        Action   = [
          "autoscaling:CompleteLifecycleAction",
          "autoscaling:RecordLifecycleActionHeartbeat"
        ]
        Resource = aws_autoscaling_group.asg.arn
      },
      {
        Sid      = "DescribeInstances"
        Effect   = "Allow"
        Action   = ["autoscaling:DescribeAutoScalingInstances"]
        Resource = "*"
      },
      {
        Sid      = "ReadInstanceTags"
        Effect   = "Allow"
        Action   = ["ec2:DescribeTags"]
        Resource = "*"
      }
    ]
  })
}
```

## Configuration

All configuration is via environment variables:

| Variable | Default | Description |
|---|---|---|
| `AWS_REGION` | auto-detected from IMDS | AWS region (falls back to `AWS_DEFAULT_REGION`) |
| `LIFECYCLE_DIR` | `/lifecycle` | Shared volume mount path |
| `POLL_INTERVAL` | `10` | Seconds between ASG state checks |
| `HEARTBEAT_INTERVAL` | `60` | Seconds between lifecycle heartbeats during termination wait |
| `MAX_TERMINATION_WAIT` | `6900` | Max seconds to wait for terminating-complete (default: 115 min) |
| `HOOK_NAME` | `{asg-name}-terminate-wait` | Override lifecycle hook name |
| `HOOK_SUFFIX` | `terminate-wait` | Suffix appended to ASG name for hook name |

## How It Works

1. On startup, fetches instance ID from EC2 IMDS (v2 with v1 fallback) and ASG name from instance tags
2. Polls `describe-auto-scaling-instances` every `POLL_INTERVAL` seconds
3. When state is `Terminating:Wait`:
   - Writes `$LIFECYCLE_DIR/terminating`
   - Sends `record-lifecycle-action-heartbeat` every `HEARTBEAT_INTERVAL` seconds
   - Waits for `$LIFECYCLE_DIR/terminating-complete` (up to `MAX_TERMINATION_WAIT`)
   - Re-asserts the `terminating` file each poll cycle (resilient to external removal)
   - Calls `complete-lifecycle-action` with result `CONTINUE` (retries up to 5 times on transient failures)
4. If `MAX_TERMINATION_WAIT` is exceeded, proceeds with termination anyway
5. If the hook's `heartbeat_timeout` expires before completion, AWS uses `default_result` (CONTINUE)

## License

MIT
