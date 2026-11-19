# 0007. Own JWT instead of an identity provider

- Status: accepted
- Date: 2026-11-19

## Context

The platform needs two roles (`CLIENTE`, `ADMIN`), a login, and a way for the
second service to trust the admin when it calls `/chaos`. An identity provider
such as Keycloak would give managed users and OAuth2/OIDC, but it is one more
container to run, configure and secure, and the project's question is about
stock and payments, not about identity.

## Decision

`api` issues its own tokens and both services validate them.

- **Passwords** are hashed with BCrypt (`auth/SecurityConfig`).
- **Tokens** are HS256 JWTs with `sub` (the e-mail), a `role` claim and a
  one-hour lifetime (`app.security.jwt-ttl`), signed by `api` on login.
- **Stateless.** Both services run Spring Security as an OAuth2 resource server
  with the same shared secret (`JWT_SECRET`, at least 32 bytes) and turn the
  `role` claim into `ROLE_<role>` for `@PreAuthorize`. `payment-service`
  protects `/chaos` this way without ever calling `api`.
- **Demo admin.** `api` creates `admin@stockrush.demo` only when
  `DEMO_ADMIN_PASSWORD` is set, so a deployment without it has no admin.

## Consequences

- No extra moving part, and the same tokens work across both services.
- A shared symmetric secret means any service holding it can mint tokens, and
  there is no revocation before expiry and no refresh token. Acceptable for a
  demo with one trust domain; not for a system with many services or users.
- Moving to Keycloak with OAuth2/OIDC is the planned evolution and is listed in
  [`docs/BACKLOG.md`](../BACKLOG.md). Only `SecurityConfig` in each service
  would change, because controllers already rely on roles, not on how a token
  was issued.
