locals {
  name       = "stockrush"
  use_domain = var.domain_name != ""
}

# --- Secrets: generated here, kept in Parameter Store, read by the instance at boot --------------------------------

resource "random_password" "jwt_secret" {
  length  = 48
  special = false
}

resource "random_password" "demo_admin" {
  length  = 20
  special = false
}

resource "aws_ssm_parameter" "jwt_secret" {
  name  = "/${local.name}/jwt-secret"
  type  = "SecureString"
  value = random_password.jwt_secret.result
}

resource "aws_ssm_parameter" "demo_admin_password" {
  name  = "/${local.name}/demo-admin-password"
  type  = "SecureString"
  value = random_password.demo_admin.result
}

# --- Network: the default VPC, and a security group that only lets CloudFront in ------------------------------------

data "aws_vpc" "default" {
  default = true
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# The CloudFront prefix list counts as ~55 rules every time it is used, and a group holds 60: one group per port.
resource "aws_security_group" "api" {
  name_prefix = "${local.name}-api-"
  description = "api, reachable only from CloudFront. No SSH: use SSM Session Manager."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "api, from CloudFront"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  egress {
    description = "Image pulls, git and package downloads"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "payment" {
  name_prefix = "${local.name}-payment-"
  description = "payment-service /chaos, reachable only from CloudFront."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "payment-service /chaos, from CloudFront"
    from_port       = 8081
    to_port         = 8081
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  lifecycle {
    create_before_destroy = true
  }
}

# --- The instance: Docker Compose runs api, payment-service, PostgreSQL, RabbitMQ, Prometheus and Grafana ------------

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "read_secrets" {
  statement {
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.jwt_secret.arn, aws_ssm_parameter.demo_admin_password.arn]
  }
}

resource "aws_iam_role" "app" {
  name_prefix        = "${local.name}-"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy" "read_secrets" {
  name   = "read-secrets"
  role   = aws_iam_role.app.id
  policy = data.aws_iam_policy_document.read_secrets.json
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.app.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "app" {
  name_prefix = "${local.name}-"
  role        = aws_iam_role.app.name
}

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  iam_instance_profile   = aws_iam_instance_profile.app.name
  vpc_security_group_ids = [aws_security_group.api.id, aws_security_group.payment.id]

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    region   = var.region
    repo_url = var.repo_url
    repo_ref = var.repo_ref
  })

  # The boot script runs once; to roll out a new version, replace the instance (or deploy through CD).
  user_data_replace_on_change = true

  tags = {
    Name = local.name
  }

  depends_on = [aws_ssm_parameter.jwt_secret, aws_ssm_parameter.demo_admin_password]
}

# A stable public name for CloudFront to use as its origin; the address survives a stop and start.
resource "aws_eip" "app" {
  instance = aws_instance.app.id
  domain   = "vpc"
}
