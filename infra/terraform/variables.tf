variable "region" {
  description = "Region for the EC2 instance, the S3 bucket and the rest of the stack."
  type        = string
  default     = "us-east-1"
}

variable "instance_type" {
  description = "t3.medium (2 vCPU, 4 GiB) runs the whole Compose stack with some headroom; the build also happens on it."
  type        = string
  default     = "t3.medium"
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
