# staging-realm.jq — derive a STAGING realm from the ONE committed template
# (Phase 29, plan 29-04; Blocker H, D-02, D-20, D-27, #299, #859).
#
#   jq --arg kind vendor   --arg host app-staging.olajay.co.uk -f infra/keycloak/staging-realm.jq realm-export.template.json
#   jq --arg kind customer --arg host app-staging.olajay.co.uk -f infra/keycloak/staging-realm.jq <customer template, fragments substituted>
#
# Run it through scripts/staging-realm-import.sh, which substitutes the customer
# template's fragment placeholders first (that template is not JSON until then),
# substitutes the vendor client secrets as whole strings afterwards, and runs the
# assertion suite on the result. This program never touches a secret value and
# never rewrites a dollar-brace token: the lower-case ones are Keycloak i18n
# built-ins and must survive byte for byte.
#
# It FAILS (jq error, non-zero exit) rather than guessing when the template no
# longer has the shape it was written against: a missing client, a missing user
# profile, or the wrong realm name for the kind.

def mailpit:
  { host: "mailpit.jtoye-staging.svc.cluster.local",
    port: "1025",
    from: "no-reply@olajay.co.uk",
    fromDisplayName: "J'Toye (staging)",
    starttls: "true",
    ssl: "false",
    auth: "false" };

def app_uri: "https://" + $host + "/*";
def app_origin: "https://" + $host;

def require_client($id):
  if ([.clients[]? | select(.clientId == $id)] | length) == 1 then .
  else error("staging-realm.jq: the template must hold exactly one client \($id)") end;

def set_client($id; f): .clients |= map(if .clientId == $id then f else . end);

# tenant_id must be a managed, admin-only user-profile attribute, or the admin
# API strips it from the user plan 29-06 creates (Keycloak 24+, measured in
# 37-08) and that user's token carries no tenant. The template already declares
# it; this keeps it admin-only and adds it if a template edit ever drops it.
def tenant_id_attribute:
  { name: "tenant_id",
    displayName: "tenant_id",
    validations: { length: { min: 36, max: 36 } },
    permissions: { view: ["admin"], edit: ["admin"] },
    multivalued: false };

def ensure_tenant_id_managed:
  if ((.components["org.keycloak.userprofile.UserProfileProvider"] // []) | length) != 1 then
    error("staging-realm.jq: the template must hold exactly one declarative user profile component")
  else
    .components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"] |=
      ( if (type == "array" and length == 1) then
          [ .[0] | fromjson
            | .attributes |= ( if any(.[]; .name == "tenant_id")
                               then map(if .name == "tenant_id" then .permissions = { view: ["admin"], edit: ["admin"] } else . end)
                               else . + [tenant_id_attribute] end )
            | tojson ]
        else error("staging-realm.jq: kc.user.profile.config must be a one-element array holding a JSON string") end )
  end;

def vendor:
  if .realm != "jtoye-dev" then error("staging-realm.jq: the vendor template realm is \(.realm), expected jtoye-dev") else . end
  | require_client("core-api") | require_client("edge-api")
  | require_client("integration-catalog-ro") | require_client("integration-orders-rw")
  | .realm = "jtoye-staging"
  # Realm-scoped paths and the default role name follow the realm. Named
  # rewrites only: any OTHER jtoye-dev string is left for the assertion suite to
  # refuse, rather than silently renamed.
  | walk( if type == "string" then
            ( gsub("/realms/jtoye-dev/"; "/realms/jtoye-staging/")
            | gsub("/admin/jtoye-dev/"; "/admin/jtoye-staging/")
            | if . == "default-roles-jtoye-dev" then "default-roles-jtoye-staging" else . end )
          else . end )
  # D-27: no seed user can exist on a public host. Service-account users stay:
  # Keycloak binds them to their clients.
  | .users |= map(select((.username // "") | startswith("service-account-")))
  | set_client("core-api";
      .redirectUris = [app_uri]
      | .webOrigins = [app_origin]
      | .attributes["post.logout.redirect.uris"] = app_uri)
  | set_client("edge-api"; .redirectUris = [] | .webOrigins = [])
  # No consumer in staging: the MCP server is deferred (#301). Disabled, not
  # deleted, so no scope mapping or service-account user dangles.
  | set_client("integration-catalog-ro"; .redirectUris = [] | .webOrigins = [] | .enabled = false)
  | set_client("integration-orders-rw"; .redirectUris = [] | .webOrigins = [] | .enabled = false)
  | .smtpServer = mailpit
  | ensure_tenant_id_managed;

def customer:
  if .realm != "jtoye-customers" then error("staging-realm.jq: the customer template realm is \(.realm), expected jtoye-customers") else . end
  | require_client("storefront-client")
  | set_client("storefront-client"; .redirectUris = [app_uri] | .webOrigins = [app_origin])
  | .verifyEmail = true
  | .smtpServer = mailpit
  # ADR-0005: Google ships disabled with empty credentials. The _note_* keys are
  # operator prose for the dev template (they quote localhost and Mailhog); they
  # stay in the template and are not copied into the staging realm.
  | .identityProviders |= map(
      if .alias == "google" then
        .enabled = false
        | .config.clientId = ""
        | .config.clientSecret = ""
        | .config |= with_entries(select(.key | startswith("_note_") | not))
      else . end);

if ($host | test("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$") | not) then error("staging-realm.jq: --arg host must be a bare host name")
elif $kind == "vendor" then vendor
elif $kind == "customer" then customer
else error("staging-realm.jq: --arg kind must be vendor or customer") end
