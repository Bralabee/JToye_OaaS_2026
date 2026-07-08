# Keycloak Configuration - JToye OaaS

## Overview

This directory contains the Keycloak realm configuration for the `jtoye-dev` realm used in development and testing.

## Files

- **realm-export.json**: Complete realm configuration including:
  - Clients: `core-api`, `frontend`
  - Groups: `tenant-a`, `tenant-b` with tenant_id attributes
  - Users: Pre-configured test users with tenant assignments
  - Protocol mappers: tenant_id claim injection into JWT
  - Realm settings: Token lifespans, security policies

## Realm Configuration Details

### Realm: `jtoye-dev`

**Clients:**
1. **core-api**
   - Client ID: `core-api`
   - Client Secret: rendered at import from `${KEYCLOAK_CLIENT_SECRET}` (set in `.env`; the rendered `realm-export.json` is gitignored — never committed)
   - Access Type: confidential
   - Service Accounts Enabled: true
   - Direct Access Grants: enabled
   - Valid Redirect URIs: `http://localhost:9090/*`

2. **frontend**
   - Client ID: `frontend`
   - Access Type: public
   - Direct Access Grants: enabled
   - Valid Redirect URIs:
     - `http://localhost:3000/*`
     - `http://localhost:3000/api/auth/callback/keycloak`
   - Web Origins: `http://localhost:3000`

### Groups & Tenant Mapping

**tenant-a Group:**
- Attribute: `tenant_id` = `00000000-0000-0000-0000-000000000001`
- Members: `tenant-a-user`

**tenant-b Group:**
- Attribute: `tenant_id` = `00000000-0000-0000-0000-000000000002`
- Members: `tenant-b-user`

### Test Users

All seed users share the password supplied via `${KC_SEED_USER_PASSWORD}` (from `.env`;
rendered into the import at container start, never committed).

| Username | Password source | Group | Tenant ID |
|----------|-----------------|-------|-----------|
| `tenant-a-user` | `${KC_SEED_USER_PASSWORD}` | tenant-a | `00000000-0000-0000-0000-000000000001` |
| `tenant-b-user` | `${KC_SEED_USER_PASSWORD}` | tenant-b | `00000000-0000-0000-0000-000000000002` |
| `admin-user` | `${KC_SEED_USER_PASSWORD}` | (none) | (none) |

### Protocol Mappers

**tenant_id Mapper:**
- Mapper Type: User Attribute
- User Attribute: tenant_id
- Token Claim Name: tenant_id
- Claim JSON Type: String
- Add to ID token: true
- Add to access token: true
- Add to userinfo: true
- Multivalued: false
- Aggregate attribute values: false

This mapper extracts the `tenant_id` attribute from the user's groups and injects it into the JWT token claims.

## Importing the Realm

### Method 1: Docker Compose (Automatic)

The realm is automatically imported when using `docker-compose.full-stack.yml`:

```bash
docker-compose -f docker-compose.full-stack.yml up
```

The `realm-export.json` file is mounted as a volume and imported on startup.

### Method 2: Manual Import via Keycloak UI

1. Start Keycloak:
   ```bash
   cd infra && docker-compose up -d keycloak
   ```

2. Access Keycloak Admin Console:
   - URL: http://localhost:8085
   - Username: `admin`
   - Password: value of `${KC_ADMIN_PASSWORD}` from your `.env`

3. Import realm:
   - Click "Add realm"
   - Click "Select file"
   - Choose `realm-export.json`
   - Click "Create"

### Method 3: Manual Import via CLI

```bash
docker exec -it jtoye-keycloak /opt/keycloak/bin/kc.sh import \
  --file /opt/keycloak/data/import/realm-export.json
```

## Exporting Realm Configuration

To export the current realm configuration (e.g., after making changes via UI):

```bash
# Export from running Keycloak container
docker exec jtoye-keycloak /opt/keycloak/bin/kc.sh export \
  --realm jtoye-dev \
  --file /tmp/realm-export.json

# Copy from container to host
docker cp jtoye-keycloak:/tmp/realm-export.json ./infra/keycloak/realm-export.json
```

**Important:** Always export after making configuration changes to keep this file up-to-date.

## Security Notes

### Development vs Production

**This configuration is for DEVELOPMENT ONLY:**
- ❌ Weak passwords — never use values like `password123` or `admin123`; supply strong rotated secrets via `.env`
- ❌ SSL not required (set to `external`)
- ❌ Client secrets in version control
- ❌ Permissive CORS settings
- ❌ Long token lifespans (3600s access tokens)

**For PRODUCTION, you MUST:**
1. ✅ Use strong, unique passwords
2. ✅ Enable SSL/TLS (`sslRequired: all`)
3. ✅ Store client secrets in secrets manager (Vault, AWS Secrets Manager)
4. ✅ Configure strict CORS policies
5. ✅ Reduce token lifespans (recommend 300s for access tokens)
6. ✅ Enable MFA/2FA for admin accounts
7. ✅ Configure proper redirect URI whitelist
8. ✅ Enable rate limiting and brute force detection
9. ✅ Regular security audits and updates

### Client Secrets

**Client secrets are never committed.** They are rendered into the gitignored
`realm-export.json` at container start from environment variables in `.env`:
- `core-api`: `${KEYCLOAK_CLIENT_SECRET}`
- `edge-api`: `${EDGE_API_CLIENT_SECRET}`

Generate fresh strong values (e.g. `openssl rand -hex 32`) per environment and store them securely.

## Token Claims

JWT tokens issued by this realm include:

```json
{
  "exp": 1234567890,
  "iat": 1234567890,
  "jti": "uuid",
  "iss": "http://localhost:8085/realms/jtoye-dev",
  "aud": "account",
  "sub": "user-uuid",
  "typ": "Bearer",
  "azp": "core-api",
  "tenant_id": "00000000-0000-0000-0000-000000000001",
  "preferred_username": "tenant-a-user",
  "email_verified": false,
  "email": "user@example.com"
}
```

The `tenant_id` claim is used by the backend for multi-tenant data isolation via RLS.

## Troubleshooting

### Realm Import Fails

**Problem:** Keycloak won't import realm
**Solution:**
1. Check file is valid JSON: `jq . realm-export.json`
2. Ensure Keycloak version compatibility (export/import on same version)
3. Check Keycloak logs: `docker logs jtoye-keycloak`

### User Can't Login

**Problem:** Authentication fails for test users
**Solution:**
1. Verify user exists in Keycloak UI
2. Check the password matches `${KC_SEED_USER_PASSWORD}` from your `.env`
3. Ensure user is enabled (not disabled)
4. Check group membership for tenant users

### tenant_id Not in JWT

**Problem:** JWT token doesn't contain tenant_id claim
**Solution:**
1. Verify user is member of tenant group (tenant-a or tenant-b)
2. Check group has tenant_id attribute set
3. Verify protocol mapper is configured correctly
4. Check mapper is enabled for the client

### Token Validation Fails

**Problem:** Backend rejects valid tokens
**Solution:**
1. Check issuer URI matches: `http://localhost:8085/realms/jtoye-dev`
2. Verify client ID matches (`core-api`)
3. Check clock sync between services
4. Verify token hasn't expired (check `exp` claim)

## Version History

- **v0.7.0** (2025-12-31): Initial realm export to version control
  - 2 clients configured (core-api, frontend)
  - 3 test users with tenant assignments
  - Protocol mappers for tenant_id injection
  - Development-ready configuration

## Related Documentation

- [Main Setup Guide](../../docs/setup/SETUP.md)
- [Credentials Document](../../docs/CREDENTIALS.md)
- [Docker Quick Start](../../docs/DOCKER_QUICK_START.md)
- [Keycloak Official Docs](https://www.keycloak.org/documentation)
