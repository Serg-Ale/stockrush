output "url" {
  description = "Where the demo answers over HTTPS."
  value       = local.use_domain ? "https://${var.domain_name}" : "https://${aws_cloudfront_distribution.site.domain_name}"
}

output "cloudfront_domain" {
  description = "With a custom name, point a CNAME for domain_name at this."
  value       = aws_cloudfront_distribution.site.domain_name
}

output "certificate_validation_record" {
  description = "Add this CNAME at the DNS provider (Hostinger) while `terraform apply` waits for the certificate."
  value = local.use_domain ? {
    name  = tolist(aws_acm_certificate.site[0].domain_validation_options)[0].resource_record_name
    type  = tolist(aws_acm_certificate.site[0].domain_validation_options)[0].resource_record_type
    value = tolist(aws_acm_certificate.site[0].domain_validation_options)[0].resource_record_value
  } : null
}

output "web_bucket" {
  description = "Upload the Angular build here (aws s3 sync) and invalidate the distribution."
  value       = aws_s3_bucket.web.bucket
}

output "distribution_id" {
  value = aws_cloudfront_distribution.site.id
}

output "instance_id" {
  description = "Open a shell with: aws ssm start-session --target <id>. The boot log is /var/log/stockrush-boot.log."
  value       = aws_instance.app.id
}

output "demo_admin_password" {
  description = "Password of admin@stockrush.demo: terraform output -raw demo_admin_password"
  value       = random_password.demo_admin.result
  sensitive   = true
}
