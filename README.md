# asg-lifecycle-sidecar

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
    network_mode: host  # required for EC2 IMDS access
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
