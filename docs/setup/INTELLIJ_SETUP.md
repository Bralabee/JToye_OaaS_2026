# IntelliJ IDEA Setup Guide

## Problem
If you see this error:
```
FATAL: password authentication failed for user "jtoye"
```

**Root Cause:** IntelliJ is running the application without the `DB_PORT=5433` environment variable.

## Solution 1: Use the 'local' Profile (EASIEST - TRY THIS FIRST!)

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

**Why this works:** We've created `application-local.yml` with the correct database port (5433) hard-coded.

## Solution 2: Use Environment Variables (If Solution 1 doesn't work)

### Step-by-Step Instructions

1. **Open Run Configurations**
   - Click the dropdown next to the Run button (shows "CoreApplication")
   - Select **"Edit Configurations..."**

2. **Add Environment Variable**
   - In the configuration window, find the **"Environment variables"** field
   - Click the folder icon or text field
   - Add: `DB_PORT=5433`
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
Successfully validated 4 migrations
Tomcat started on port 9090 (http) with context path '/'
Started CoreApplication in X.XXX seconds
```

## Alternative: Use the Run Script

If IntelliJ configuration is problematic, use the terminal:
```bash
./run-app.sh
```

This script automatically sets all required environment variables.

## Common Mistakes

❌ **Wrong:** Leaving environment variables empty
❌ **Wrong:** Using port 5432 (default PostgreSQL port)
❌ **Wrong:** Running without any environment variables

✅ **Correct:** `DB_PORT=5433` set in environment variables
✅ **Correct:** Using the `./run-app.sh` script

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
- `DB_USER=jtoye` (database user)
- `DB_PASSWORD=secret` (database password)

But **`DB_PORT=5433` is the critical one** that must be set!
