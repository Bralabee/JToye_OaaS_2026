#!/usr/bin/env python3
"""Execute both real workflow preflights under the GitHub Actions shell.

Only the external realm probe is replaced with a deterministic exit code; the
workflow's parsing, URI selection and fail-open/closed branches run unchanged.
No deployed realm, credentials or Kubernetes cluster is needed.
"""

import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/ci-cd.yaml"
STEP = "Pre-flight — the deployed realm accepts this overlay's vendor post-logout redirect URI (FE-1 / E-5)"


def preflights():
    blocks = re.findall(
        rf"^      - name: {re.escape(STEP)}\n        run: \|\n((?:          .*\n|\n)+)",
        WORKFLOW.read_text(), re.MULTILINE,
    )
    assert len(blocks) == 2, f"expected both deployment preflights, found {len(blocks)}"
    for environment, block in zip(("staging", "production"), blocks):
        script = "\n".join(line[10:] for line in block.splitlines())
        render_line = f"RENDER=/tmp/{environment}-render.yaml"
        assert script.count(render_line) == 1, f"missing {environment} render input"
        yield environment, script.replace(render_line, 'RENDER="$TEST_RENDER"')


def main():
    failures = []
    arms = 0
    with tempfile.TemporaryDirectory(prefix="logout-preflight-", dir=ROOT) as temp:
        render = Path(temp) / "render.yaml"
        for environment, script in preflights():
            for flag in ("true", "false"):
                values = {
                    "keycloak.public.issuer.uri": "https://auth.example/realms/vendors",
                    "keycloak.client-id": "vendor-client",
                    "frontend.url": "https://vendor.example/",
                    "vendor.logout-complete.enabled": flag,
                }
                for missing in (None, *values):
                    render.write_text("".join(
                        f'  {key}: "{value}"\n' for key, value in values.items() if key != missing
                    ))
                    for probe_rc in (0, 1, 2):
                        arms += 1
                        expected_uri = "https://vendor.example/" + (
                            "api/vendor-auth/logout-complete" if flag == "true" else "auth/signin"
                        )
                        # A function doubles ONLY the network probe, not its caller.
                        probe = f"""bash() {{
  [ "$#" -eq 1 ] && [ "$1" = scripts/check-keycloak-logout-uri.sh ] || return 90
  [ "$KC_ISSUER" = https://auth.example/realms/vendors ] || return 91
  [ "$KC_CLIENT_ID" = vendor-client ] || return 92
  [ "$POST_LOGOUT_REDIRECT_URI" = {shlex.quote(expected_uri)} ] || return 93
  printf 'PROBE_CALLED\\n'
  return "$PROBE_RC"
}}
"""
                        result = subprocess.run(
                            ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", probe + script],
                            cwd=ROOT, env={**os.environ, "TEST_RENDER": str(render), "PROBE_RC": str(probe_rc)},
                            text=True, capture_output=True, timeout=10,
                        )
                        output = result.stdout + result.stderr
                        expected_rc = 2 if missing else probe_rc if flag == "true" else 0
                        advisory = not missing and flag == "false" and probe_rc != 0
                        fatal = missing is not None or (flag == "true" and probe_rc != 0)
                        correct = (
                            result.returncode == expected_rc
                            and output.count("PROBE_CALLED") == (0 if missing else 1)
                            and ("ADVISORY" in output) == advisory
                            and ("FATAL" in output) == fatal
                        )
                        if not correct:
                            failures.append(
                                f"{environment} flag={flag} probe={probe_rc} missing={missing}: "
                                f"exit={result.returncode}, expected={expected_rc}\n{output}"
                            )
    if failures:
        print("FAIL: logout preflight control flow\n" + "\n".join(failures))
        return 1
    print(f"PASS: {arms} logout preflight arms under bash -e -o pipefail")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())