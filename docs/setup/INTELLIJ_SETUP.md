# IntelliJ IDEA Setup Guide

## Problem
If you see this error:
```
FATAL: password authentication failed for user "jtoye"
```

**Root Cause:** IntelliJ is running the application without the `DB_PORT=5433` environment variable.

## Solution 1: Use the 'local' Profile (fewest settings — try this first)

> You still need credentials. The `local` profile supplies the PORT (5433) and the runtime
> ROLE as defaults; it does NOT supply a password, and it no longer carries one. Set
> `DB_PASSWORD` in the run configuration's Environment variables (or export it in the shell
> IntelliJ inherits) using the value from your `.env`.

### Step-by-Step Instructions

1. **Open Run Configurations**
   - Click the dropdown next to the Run button (shows "CoreApplication")
   - Select **"Edit Configurations..."**

2. **Set Active Profile**
   - In the configuration window, find **"Active profiles"** or **"VM options"**
   - If you see "Active profiles": Type `local`
   - If you see "VM options": Add `-Dspring.profiles.active=local`
   - Click **OK**

3. **Apply and Save**
   - Click **"Apply"**
   - Click **"OK"**

4. **Run the Application**
   - Click the green Run button
   - Application should start on port 9090

**Why this works:** `application-local.yml` defaults the connection to port 5433 and to the
`jtoye_runtime` application role, so those two are the settings you do not have to type.
Every value in that file is config-injected (`${DB_PORT:5433}`, `${DB_USER:jtoye_runtime}`,
`${DB_PASSWORD}`) — it used to hard-code `jtoye_app` / `secret`, which could not authenticate
and, even with a correct password, is a role the application refuses to start as because it
OWNS the tables. `DB_PASSWORD` has no default on purpose: the boot fails loudly on an
unresolved placeholder rather than silently trying an empty password.

## Solution 2: Use Environment Variables (no profile)

### Step-by-Step Instructions

1. **Open Run Configurations**
   - Click the dropdown next to the Run button (shows "CoreApplication")
   - Select **"Edit Configurations..."**

2. **Add Environment Variable**
   - In the configuration window, find the **"Environment variables"** field
   - Click the folder icon or text field
   - Add ALL THREE — `DB_PORT` alone is not enough. Without a profile, `application.yml`
     resolves `${DB_USER:jtoye_app}` (the MIGRATOR role, which the application refuses to
     start as) and `${DB_PASSWORD:}` (EMPTY, which does not authenticate):

     ```
     DB_PORT=5433
     DB_USER=jtoye_runtime
     DB_PASSWORD=<the value from your .env>
     ```
   - Click **OK**

3. **Apply and Save**
   - Click **"Apply"**
   - Click **"OK"**

4. **Run the Application**
   - Click the green Run button
   - Application should start on port 9090

### Visual Guide

```
Run Configuration Window:
┌─────────────────────────────────────────┐
│ Name: CoreApplication                   │
│ Main class: uk.jtoye.core.CoreApplication│
│                                         │
│ Environment variables:                  │
│ ┌─────────────────────────────────────┐ │
│ │ DB_PORT=5433                        │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ [Apply] [OK] [Cancel]                   │
└─────────────────────────────────────────┘
```

## Verification

When configured correctly, you should see in the logs:
```
Database: jdbc:postgresql://localhost:5433/jtoye (PostgreSQL 15.13)
Successfully validated N migrations        # N = the current schema head; see README.md
Tomcat started on port 9090 (http) with context path '/'
Started CoreApplication in X.XXX seconds
```

## Alternative: Use the Run Script

If IntelliJ configuration is problematic, use the terminal:
```bash
./scripts/run-app.sh
```

This script automatically sets all required environment variables.

## Common Mistakes

❌ **Wrong:** Leaving environment variables empty
❌ **Wrong:** Using port 5432 (default PostgreSQL port)
❌ **Wrong:** Running without any environment variables

✅ **Correct:** `DB_PORT=5433` set in environment variables
✅ **Correct:** Using the `./scripts/run-app.sh` script

## Troubleshooting

### If you still get the error:
1. **Verify IntelliJ saved the configuration**
   - Open Edit Configurations again
   - Check that `DB_PORT=5433` is still there

2. **Check PostgreSQL is running**
   ```bash
   docker ps | grep jtoye-postgres
   ```

3. **Verify the port is correct**
   ```bash
   docker ps | grep jtoye-postgres
   # Should show: 0.0.0.0:5433->5432/tcp
   ```

4. **Test database connection manually**
   ```bash
   docker exec jtoye-postgres psql -U jtoye -d jtoye -c "SELECT 1;"
   ```

## Additional Configuration (Optional)

You can also set:
- `SERVER_PORT=9090` (API port, default is 9090)
- `DB_HOST=localhost` (database host)
- `DB_NAME=jtoye` (database name)
- `DB_USER=jtoye_runtime` (application role — **never** `jtoye`, the superuser: it bypasses RLS and `DatabaseConfigurationValidator` refuses to start; and never `jtoye_app`, which OWNS the tables and is refused for the same reason)
- `DB_PASSWORD` (database password — take the value from your `.env`, do not type a literal)

**All three are required.** `DB_PORT` alone leaves the application on the base default role
with an empty password, which is the exact "password authentication failed" this guide opens
with.
