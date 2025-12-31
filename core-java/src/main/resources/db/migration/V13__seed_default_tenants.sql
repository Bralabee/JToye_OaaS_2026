-- Seed default tenants for Keycloak users
-- These UUIDs match the tenant_id claims in Keycloak JWT tokens

INSERT INTO tenants (id, name, created_at) VALUES
  ('00000000-0000-0000-0000-000000000001'::uuid, 'Tenant A', NOW()),
  ('00000000-0000-0000-0000-000000000002'::uuid, 'Tenant B', NOW())
ON CONFLICT (id) DO NOTHING;
