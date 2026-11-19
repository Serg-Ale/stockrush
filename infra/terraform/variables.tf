variable "region" {
  description = "Region for the EC2 instance, the S3 bucket and the rest of the stack."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "t3.small (2 vCPU, 2 GiB) is the cheapest free-plan-eligible type that fits the stack, with swap and capped JVM heaps; the free plan refuses t3.medium. c7i-flex.large (4 GiB) has headroom but spends the credits about four times faster."
  type        = string
  default     = "t3.small"
}

variable "domain_name" {
  description = "Public host name, for example stockrush.serg-ale.tech. Empty serves the demo on the *.cloudfront.net name, which already has HTTPS."
  type        = string
  default     = ""
}

variable "repo_url" {
  description = "Public Git repository the instance clones and builds."
  type        = string
  default     = "https://github.com/Serg-Ale/stockrush.git"
}

variable "repo_ref" {
  description = "Branch or tag the instance checks out."
  type        = string
  default     = "main"
}
