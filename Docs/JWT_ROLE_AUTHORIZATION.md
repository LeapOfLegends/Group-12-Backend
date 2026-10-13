# JWT Role Authorization

This guide describes how the backend authenticates API requests with JWT bearer tokens and applies role-based access checks. The backend is an OAuth2 resource server: the auth service issues tokens, and the backend validates them before allowing protected requests to reach controllers.

## Responsibilities and token contract

The NestJS auth service signs login tokens using HS256. A token contains a `sub` claim with the client ID, an `email` claim, a `roles` array, and standard issued-at and expiration claims. The current client login flow assigns the `CLIENT` role. The backend and auth service must use the same `JWT_SECRET`; the backend rejects a signing secret shorter than 32 UTF-8 bytes.

Example token claims (illustrative only):

```json
{
  "sub": "123",
  "email": "client@example.com",
  "roles": ["CLIENT"],
  "iat": 1791853200,
  "exp": 1791856800
}
```

Do not put real tokens, signing secrets, or personal data in documentation, source control, logs, or issue reports.

## Request flow

1. The client logs in to the auth service and receives an access token.
2. The client sends the token to the backend in `Authorization: Bearer <token>`.
3. Spring Security's resource-server filter decodes the token, requires an HS256 signature made with the configured shared secret, checks standard token time claims, and verifies `sub` parses as a positive client ID.
4. The `roles` claim is converted into Spring authorities by adding the `ROLE_` prefix. For example, `CLIENT` becomes `ROLE_CLIENT`, which `hasRole("CLIENT")` checks.
5. The HTTP security chain permits the explicitly public paths and requires authentication for all other requests. Method security then checks each controller's `@PreAuthorize` rule.
6. For client self-service endpoints, controllers use the already verified token's `sub` as the client ID. The client does not choose its own identity by supplying an ID in the self-service URL or request body.
7. The controller calls the service layer and returns the API response. A missing or invalid token normally results in `401 Unauthorized`; a valid token without the required role results in `403 Forbidden`.

## Public paths and role access

The HTTP security configuration permits these paths without a token:

- `GET /db/ping`
- Swagger UI paths: `/swagger-ui/**` and `/swagger-ui.html`
- OpenAPI document paths: `/v3/api-docs/**`

All other paths require a valid authenticated token. Controller method security further limits access:

| Role | Current access pattern |
| --- | --- |
| `CLIENT` | Own client profile, balance, holdings, and orders; instrument reads. Self-service client IDs come from JWT `sub`. |
| `SUPER_ADMIN` | Admin management and broad client, balance, holding, order, and instrument operations. |
| `FINANCE` | Account and balance related operations, plus staff order and holding reads and instrument reads. |
| `OPERATIONS` | Operational client and holding management, staff order reads, and instrument reads. |

These are summaries of current controller annotations, not a substitute for checking the endpoint's `@PreAuthorize` expression. For example, client deletion and admin management are restricted to `SUPER_ADMIN`; client balance administration allows `SUPER_ADMIN` and `FINANCE`; and staff reads generally allow `SUPER_ADMIN`, `FINANCE`, or `OPERATIONS`. Review and update this table when endpoint authorization changes.

## Local development

The backend reads `JWT_SECRET` through `jwt.secret` in `src/main/resources/application.yml`. Set it in the local process environment or your untracked local environment configuration. Use the same value in the auth service and backend, with at least 32 UTF-8 bytes. Keep it secret and do not commit it.

When calling protected endpoints locally, obtain a token from the auth service and provide it as a bearer token. If testing a role that the auth service does not currently issue through client login, use a test fixture or test-generated signed token; do not weaken production authorization to make local testing easier.

## Tests and maintenance

The security behavior is covered by tests in `src/test/java/group12/security` and controller security tests under `src/test/java/group12/Controller`. These cover token decoding and validation, role-based access, missing tokens, client isolation using the JWT subject, and denied access for insufficient roles. Run the backend's Maven test suite when changing JWT validation, role mapping, or controller access rules.

When adding or changing an endpoint:

1. Decide which roles may call it and add an explicit `@PreAuthorize` rule where appropriate. The HTTP-level default only requires authentication; it does not select the appropriate business role.
2. For self-service operations, derive the client ID from the verified JWT subject and scope database/service lookups to that ID.
3. Add tests for an allowed role, a disallowed authenticated role, and a missing or invalid token where relevant.
4. Update this guide's role summary if the effective access patterns change.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| `401 Unauthorized` | Missing or malformed bearer token, expired token, invalid HS256 signature, mismatched `JWT_SECRET`, invalid timestamps, or missing/nonpositive/non-numeric `sub`. |
| `403 Forbidden` | The token is valid, but its `roles` claim does not include a role accepted by the endpoint. Role values are expected without the `ROLE_` prefix, such as `CLIENT`. |
| Backend fails during startup | `JWT_SECRET` is missing or shorter than the required 32 UTF-8 bytes. |
| Role check unexpectedly fails | Check that `roles` is an array of role strings in the signed token and that the controller's `@PreAuthorize` role matches the claim. |

Do not use client-supplied IDs as a replacement for the JWT subject on self-service paths, and do not treat possession of a valid token alone as authorization for every operation.
