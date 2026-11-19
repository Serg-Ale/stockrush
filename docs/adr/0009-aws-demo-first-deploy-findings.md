# 0009. The AWS demo on the free plan: what the first deploy found

- Status: accepted (the deploy is not finished: see "Where it stands")
- Date: 2026-11-19

## Context

[`infra/terraform`](../../infra/terraform) was written and validated without an AWS account. On 2026-11-19 the
account was created (free plan, USD 100 of credit, no charge unless upgraded to the paid plan) and the first real
`terraform apply` ran. `terraform validate` and `plan` had passed; five things only the real run could show.

## What the first apply found

1. **Security group limit.** The CloudFront prefix list counts as about 55 rules wherever it is used, and a group
   holds 60 (`RulesPerSecurityGroupLimitExceeded`). Two ingress rules (8080 and 8081) in one group do not fit.
   Decision: one security group per port.
2. **Instance size.** The free plan refuses `t3.medium` (`The specified instance type is not eligible for Free Tier`).
   The accepted types (`aws ec2 describe-instance-types --filters Name=free-tier-eligible,Values=true`) are
   `t3`/`t4g`/`t8i`/`t9g` micro and small, `c7i-flex.large` and `m7i-flex.large`. Decision: `t3.small` (2 GiB), the
   cheapest that can hold the stack, with a 2 GiB swap file and capped JVM heaps in `compose.aws.yaml`. About
   US$ 21 a month, which the credit covers for roughly six months. `c7i-flex.large` (4 GiB) is the fallback and
   spends the credit about four times faster.
3. **CloudFront needs a verified account.** A new account answers `Your account must be verified before you can add
   new CloudFront resources` (403, `CreateDistributionWithTags`). Not fixable from code: a support case (Account
   and billing, service Account Activation) was opened with the request IDs. Until it is answered there is no URL.
4. **The instance cannot clone a private repository.** The boot script runs `git clone` over HTTPS, and the
   repository is private (`could not read Username for 'https://github.com'`), so the Compose stack never started.
   The Terraform variable `repo_url` was documented as a public repository; that assumption was never true.
5. **A wrong-branch apply.** An `apply` ran against `main`'s older configuration after a command had switched
   branches, and destroyed the instance that was still booting. Nothing was lost, but it shows the local-state
   workflow is fragile: apply only from a clean, known branch, and never in the same command that switches it.

## Decision

Keep the design (EC2 with Compose, S3 and CloudFront, ACM at the custom name) with the fixes above, which are in
PR #83. For item 4, the instance must be able to read the code. Options, with the one chosen once the user decides:

- make the repository public, which matches the goal of exposing the portfolio projects and needs no secret;
- keep it private and give the instance a read-only deploy key or token in Parameter Store (more moving parts);
- build images in CI and have the instance pull them from a registry (this is ticket #34, and it still needs the
  Compose files on the instance).

## Where it stands

Created and running: IAM, SSM parameters, the S3 bucket, the CloudFront function and origin access control, a
`t3.small` instance and its Elastic IP. Not created: the CloudFront distribution and the bucket policy (item 3).
Not working: the stack on the instance (item 4). Costs so far are cents of credit. `terraform destroy` removes it all.

## Consequences

- `terraform validate` and `plan` are not evidence that an apply works; the acceptance criteria of #33 (apply from
  scratch, HTTPS, destroy) stay open until a full apply succeeds.
- Account-level limits (security group rules, free-plan instance types, CloudFront verification) belong in
  `infra/terraform/README.md` so the next person finds them before the error does.
