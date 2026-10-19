#!/usr/bin/env python3
"""keyvault-chain.py: the Key Vault secret chain of a RENDERED kustomize build (plan 29-12, D-23).

    secretKeyRef (Secret, key)  ->  SecretProviderClass secretObjects  ->  objectName  ->  Key Vault

Usage (stdlib + PyYAML; run as `python3 k8s/scripts/keyvault-chain.py ...`):

    keyvault-chain.py check   <render.yaml> [--verbose]   rc 0 chain holds, 1 broken, 2 VOID
    keyvault-chain.py objects <render.yaml>               the derived Key Vault object list
    keyvault-chain.py access  <render.yaml>               `<clientID> TAB <objectName>` per SPC object

`objects` prints the sorted, de-duplicated objectNames of every SPC's parameters.objects, one per
line, and ONLY when `check` holds: a list read from a broken chain would hand the write-once
writer and the deploy preflight (plan 29-13) a wrong answer that looks right. Nobody writes that
list by hand (D-23).

`access` prints, under the same condition, one `<clientID>\t<objectName>` line per object each SPC
names (sorted, de-duplicated): the per-identity grant list plan 29-13's `--assign-access` and its
preflight read. A role on exactly these objects, at per-secret scope, is "each identity reads only
its own SPC's objects" (T-29-50); the list comes from the same checked chain as `objects`.

WHAT `check` ASSERTS (each rule is a defect class, not a style point)

  (a) every (Secret, key) a pod template consumes (env secretKeyRef, envFrom secretRef, a secret
      volume or projected source, and the RabbitmqCluster's secretBackend.externalSecret, which the
      operator reads and which is attributed to Deployment/secret-sync) is produced by exactly one
      SPC secretObjects entry, and that SPC is MOUNTED by the consuming pod template. The CSI driver
      creates a synced Secret only while a pod mounts its SPC and deletes it with the last such pod,
      so a consumer that does not mount its producer reads a Secret that is there only by accident
      of some other pod's lifetime. An `optional: true` ref is held to the same rule (T-29-45): an
      optional ref with no producer starts the pod silently unconfigured.
      ONE other producer is recognised (plan 29-22): a cert-manager Certificate in the same render
      writes the Secret its spec.secretName names. Such a Secret holds no Key Vault material (a
      cluster-internal certificate and key cert-manager issues and renews), so its consumers need
      no SPC mount; they may read only the keys cert-manager writes (ca.crt, tls.crt, tls.key) or
      the whole Secret, and no SPC may declare the same Secret name (rule (b): two producers).
  (b) a Secret name is produced by ONE SPC. Two producers overwrite each other's keys (T-29-44).
  (c) every secretObjects objectName is one of that SPC's parameters.objects (the file the driver
      mounts and syncs from).
  (d) the clientID of each SPC a pod mounts equals the azure.workload.identity/client-id annotation
      of the ServiceAccount the pod runs as (one identity per workload, T-29-43); clientID and
      tenantId are GUIDs (an unreplaced REPLACED-BY-OVERLAY placeholder fails here, closed);
      keyvaultName matches ^[a-z0-9-]{3,24}$; every objectName matches ^[0-9A-Za-z-]{1,127}$.
      And no two ServiceAccounts that mount an SPC share one client-id: a pair of workloads wired
      to ONE identity is consistent with itself (each SPC still equals its own pod's SA), so only a
      cross-workload comparison sees it (found by a real-tree break arm, plan 29-12).
  (e) each objectName maps back to exactly ONE canonical (Secret, key): the pair whose
      `<secret>--<key>` spelling (`_` and `.` mapped to `-`, 29-RESEARCH A6) it is. Two different
      pairs collapsing onto one object name would make two values share one vault entry.
  and: every SPC is mounted by some pod template (an unmounted SPC never syncs anything); every
      mounted SPC exists; the mount is a csi volume of secrets-store.csi.k8s.io that a container
      actually mounts (the kubelet skips a pod volume no container references).

VOID (rc 2), never a pass: PyYAML not importable, the render unreadable or unparseable, zero
SecretProviderClasses, or zero consumed Secret references. "Found nothing" is not "clean".
"""

import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - exercised by the selftest's `python3 -S` arm
    sys.stderr.write("VOID: PyYAML is not importable; the render cannot be read.\n")
    sys.exit(2)

DRIVER = "secrets-store.csi.k8s.io"
CLIENT_ID_ANNOTATION = "azure.workload.identity/client-id"
GUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
VAULT_RE = re.compile(r"^[a-z0-9-]{3,24}$")
OBJECT_RE = re.compile(r"^[0-9A-Za-z-]{1,127}$")
# The keys of the RabbitMQ operator's own default-user Secret (v2.23.1
# internal/resource/default_user_secret.go, read in plan 29-10): what an externalSecret must hold.
RABBITMQ_EXTERNAL_SECRET_KEYS = ("username", "password", "default_user.conf")
EXTERNAL_SECRET_CONSUMER = "Deployment/secret-sync"
POD_KINDS = ("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob")
# The keys cert-manager writes into a Certificate's Secret (cert-manager.io/docs/usage/certificate,
# "the Secret ... tls.crt, tls.key and ca.crt").
CERT_MANAGER_KEYS = ("ca.crt", "tls.crt", "tls.key")


def void(msg):
    sys.stderr.write(f"VOID: {msg}\n")
    sys.exit(2)


def object_name_for(secret, key):
    """The canonical Key Vault object name of a (Secret, key): `<secret>--<key>`, `_`/`.` -> `-`."""
    return f"{secret}--{key}".replace("_", "-").replace(".", "-")


def pod_template(doc):
    spec = doc.get("spec") or {}
    if doc.get("kind") == "CronJob":
        return ((spec.get("jobTemplate") or {}).get("spec") or {}).get("template")
    return spec.get("template")


def load(path):
    try:
        with open(path, encoding="utf-8") as fh:
            docs = [d for d in yaml.safe_load_all(fh) if isinstance(d, dict)]
    except OSError as exc:
        void(f"cannot read the render {path}: {exc}")
    except yaml.YAMLError as exc:
        void(f"cannot parse the render {path}: {exc}")
    if not docs:
        void(f"the render {path} holds no document")
    return docs


def parse_objects(spc_name, raw, errors):
    """parameters.objects is a YAML string: `array:` of YAML strings, each objectName/objectType."""
    names = []
    try:
        outer = yaml.safe_load(raw) if isinstance(raw, str) else None
        items = (outer or {}).get("array") or []
        for item in items:
            entry = yaml.safe_load(item) if isinstance(item, str) else item
            name = (entry or {}).get("objectName")
            if (entry or {}).get("objectType") != "secret":
                errors.append(f"SecretProviderClass {spc_name}: object {name!r} has objectType "
                              f"{(entry or {}).get('objectType')!r}, expected 'secret'")
            if not isinstance(name, str):
                errors.append(f"SecretProviderClass {spc_name}: an objects entry has no objectName")
                continue
            names.append(name)
    except yaml.YAMLError as exc:
        errors.append(f"SecretProviderClass {spc_name}: parameters.objects is not parseable: {exc}")
    if not names:
        errors.append(f"SecretProviderClass {spc_name}: parameters.objects lists no object")
    return names


def index(docs, errors):
    spcs, sas, templates = {}, {}, {}
    external = []
    certs = {}
    for d in docs:
        kind = d.get("kind")
        meta = d.get("metadata") or {}
        name = meta.get("name")
        if kind == "SecretProviderClass":
            spec = d.get("spec") or {}
            params = spec.get("parameters") or {}
            produced = []
            for so in spec.get("secretObjects") or []:
                for item in so.get("data") or []:
                    produced.append((so.get("secretName"), item.get("key"), item.get("objectName")))
            spcs[name] = {
                "provider": spec.get("provider"),
                "clientID": str(params.get("clientID", "")),
                "tenantId": str(params.get("tenantId", "")),
                "keyvaultName": str(params.get("keyvaultName", "")),
                "usePodIdentity": str(params.get("usePodIdentity", "")),
                "objects": parse_objects(name, params.get("objects"), errors),
                "produced": produced,
                "secretNames": [so.get("secretName") for so in spec.get("secretObjects") or []],
            }
        elif kind == "ServiceAccount":
            sas[name] = str((meta.get("annotations") or {}).get(CLIENT_ID_ANNOTATION, ""))
        elif kind == "Certificate" and str(d.get("apiVersion", "")).startswith("cert-manager.io/"):
            sn = (d.get("spec") or {}).get("secretName")
            if sn:
                certs.setdefault(sn, []).append(name)
        elif kind == "RabbitmqCluster":
            ext = (((d.get("spec") or {}).get("secretBackend") or {}).get("externalSecret") or {}).get("name")
            if ext:
                external.append((f"RabbitmqCluster/{name}", ext))
        elif kind in POD_KINDS:
            tpl = pod_template(d) or {}
            pspec = tpl.get("spec") or {}
            refs = []
            containers = (pspec.get("initContainers") or []) + (pspec.get("containers") or [])
            mounted_names = set()
            for c in containers:
                for vm in c.get("volumeMounts") or []:
                    mounted_names.add(vm.get("name"))
                for e in c.get("env") or []:
                    ref = (e.get("valueFrom") or {}).get("secretKeyRef")
                    if ref:
                        refs.append((ref.get("name"), ref.get("key"), "env " + str(e.get("name"))))
                for ef in c.get("envFrom") or []:
                    if ef.get("secretRef"):
                        refs.append((ef["secretRef"].get("name"), "*", "envFrom"))
            mounts = set()
            for v in pspec.get("volumes") or []:
                if v.get("secret"):
                    refs.append((v["secret"].get("secretName"), "*", "volume " + str(v.get("name"))))
                for src in (v.get("projected") or {}).get("sources") or []:
                    if src.get("secret"):
                        refs.append((src["secret"].get("name"), "*", "projected volume " + str(v.get("name"))))
                csi = v.get("csi") or {}
                if csi.get("driver") == DRIVER:
                    spc = (csi.get("volumeAttributes") or {}).get("secretProviderClass")
                    if v.get("name") not in mounted_names:
                        errors.append(f"{kind}/{name}: csi volume {v.get('name')!r} (SecretProviderClass "
                                      f"{spc}) is mounted by no container; the kubelet skips it, so the "
                                      f"driver never syncs its Secrets")
                    elif spc:
                        mounts.add(spc)
            templates[f"{kind}/{name}"] = {"sa": pspec.get("serviceAccountName"), "mounts": mounts, "refs": refs}
    return spcs, sas, templates, external, certs


def check(docs, verbose=False):
    errors = []
    spcs, sas, templates, external, certs = index(docs, errors)
    if not spcs:
        void("the render holds no SecretProviderClass; a chain with no producer cannot be checked "
             "(a target that includes components/azure-keyvault must render its SPCs)")

    # Consumers: (workload, secret, key, how).
    consumers = []
    for wl, t in sorted(templates.items()):
        for secret, key, how in t["refs"]:
            consumers.append((wl, secret, key, how))
    for cr, secret in external:
        for key in RABBITMQ_EXTERNAL_SECRET_KEYS:
            consumers.append((EXTERNAL_SECRET_CONSUMER, secret, key, f"externalSecret of {cr}"))
    if not consumers:
        void("the render consumes no Secret at all; the chain check would pass vacuously")
    if external and EXTERNAL_SECRET_CONSUMER not in templates:
        errors.append(f"{external[0][0]} reads Secret {external[0][1]} through externalSecret, but "
                      f"there is no {EXTERNAL_SECRET_CONSUMER} to keep it synced (the operator has no pod "
                      f"that mounts a SecretProviderClass)")

    # (b) one producer per Secret name.
    producers = {}
    for spc, s in sorted(spcs.items()):
        for sn in s["secretNames"]:
            producers.setdefault(sn, []).append(spc)
    for sn, cs in sorted(certs.items()):
        for spc in producers.get(sn, []):
            errors.append(f"Secret {sn} has two producers: SecretProviderClass {spc} and cert-manager Certificate "
                          f"{', '.join(cs)}; the CSI driver and cert-manager would overwrite each other's keys")
        if len(cs) > 1:
            errors.append(f"Secret {sn} has two producers: cert-manager Certificates {', '.join(cs)}")
    for sn, ps in sorted(producers.items()):
        if len(ps) > 1:
            errors.append(f"Secret {sn} has two producers: SecretProviderClasses {', '.join(ps)} both "
                          f"declare it in secretObjects, and the CSI driver would overwrite one with the other")

    # (a) every consumed (Secret, key) has its producer, mounted by the consumer.
    for wl, secret, key, how in consumers:
        ps = producers.get(secret, [])
        if not ps and secret in certs:
            if key != "*" and key not in CERT_MANAGER_KEYS:
                errors.append(f"{wl} reads {secret}/{key} ({how}): Secret {secret} is written by cert-manager "
                              f"Certificate {', '.join(certs[secret])}, which holds only {', '.join(CERT_MANAGER_KEYS)}")
            continue
        if not ps:
            errors.append(f"{wl} reads {secret}/{key} ({how}), which no SecretProviderClass produces")
            continue
        if key != "*":
            have = [spc for spc in ps if any(sn == secret and k == key for sn, k, _ in spcs[spc]["produced"])]
            if not have:
                errors.append(f"{wl} reads {secret}/{key} ({how}): SecretProviderClass {ps[0]} produces "
                              f"Secret {secret} without key {key}")
                continue
        mounts = templates.get(wl, {}).get("mounts", set())
        if not any(spc in mounts for spc in ps):
            errors.append(f"{wl} reads {secret}/{key} ({how}), produced by {', '.join(ps)}, which {wl} does "
                          f"not mount; the CSI driver syncs a Secret only while a pod mounts its producer")

    # (c), (d), (e) per SPC.
    canonical = {}
    for spc, s in sorted(spcs.items()):
        if s["provider"] != "azure":
            errors.append(f"SecretProviderClass {spc}: provider is {s['provider']!r}, expected 'azure'")
        if s["usePodIdentity"] != "false":
            errors.append(f"SecretProviderClass {spc}: usePodIdentity is {s['usePodIdentity']!r}, expected "
                          f"\"false\" (Workload Identity)")
        for field in ("clientID", "tenantId"):
            if not GUID_RE.match(s[field]):
                errors.append(f"SecretProviderClass {spc}: {field} {s[field]!r} is not a GUID (an unreplaced "
                              f"placeholder means the overlay's replacements did not land)")
        if not VAULT_RE.match(s["keyvaultName"]):
            errors.append(f"SecretProviderClass {spc}: keyvaultName {s['keyvaultName']!r} does not match "
                          f"^[a-z0-9-]{{3,24}}$")
        objs = set(s["objects"])
        for o in sorted(objs):
            if not OBJECT_RE.match(o):
                errors.append(f"SecretProviderClass {spc}: objectName {o!r} does not match ^[0-9A-Za-z-]{{1,127}}$")
        for sn, key, obj in s["produced"]:
            if obj not in objs:
                errors.append(f"SecretProviderClass {spc}: secretObjects {sn}/{key} syncs from objectName "
                              f"{obj!r}, which is not in its parameters.objects (nothing is mounted under that name)")
            if obj == object_name_for(sn, key):
                canonical.setdefault(obj, set()).add((sn, key))
        mounted_by = [wl for wl, t in sorted(templates.items()) if spc in t["mounts"]]
        if not mounted_by:
            errors.append(f"SecretProviderClass {spc} is mounted by no pod template; its Secrets would never sync")
        for wl in mounted_by:
            sa = templates[wl]["sa"]
            if not sa or sa == "default":
                errors.append(f"{wl} mounts {spc} but runs as ServiceAccount {sa!r}; it needs its own")
            elif sa not in sas:
                errors.append(f"{wl} mounts {spc} as ServiceAccount {sa}, which the render does not hold")
            elif sas[sa] != s["clientID"]:
                errors.append(f"{wl} mounts {spc}, whose clientID {s['clientID']!r} differs from ServiceAccount "
                              f"{sa}'s {CLIENT_ID_ANNOTATION} {sas[sa]!r}: the fetch would run as another identity")
    # (d') one identity per workload: the client-ids of the ServiceAccounts that mount an SPC
    # are pairwise distinct.
    by_client = {}
    for wl, t in sorted(templates.items()):
        if t["mounts"] and t["sa"] in sas and sas[t["sa"]]:
            by_client.setdefault(sas[t["sa"]], set()).add(t["sa"])
    for cid, names in sorted(by_client.items()):
        if len(names) > 1:
            errors.append(f"ServiceAccounts {', '.join(sorted(names))} share {CLIENT_ID_ANNOTATION} {cid!r}: "
                          f"each secret-reading workload must have its own identity, so it can be granted only "
                          f"its own Key Vault objects")
    for wl, t in sorted(templates.items()):
        for spc in sorted(t["mounts"]):
            if spc not in spcs:
                errors.append(f"{wl} mounts SecretProviderClass {spc}, which the render does not hold")

    # (e) every objectName has exactly one canonical (Secret, key).
    all_objects = sorted({o for s in spcs.values() for o in s["objects"]})
    mapping = {}
    for o in all_objects:
        pairs = canonical.get(o, set())
        if not pairs and "--" in o:
            sec, key = o.split("--", 1)
            if object_name_for(sec, key) == o:
                pairs = {(sec, key)}
        if len(pairs) != 1:
            shown = ", ".join(f"{a}/{b}" for a, b in sorted(pairs)) or "none"
            errors.append(f"Key Vault object {o} maps back to {len(pairs)} canonical (Secret, key) pairs "
                          f"({shown}); it must be exactly one")
        else:
            mapping[o] = next(iter(pairs))

    if verbose:
        for o in all_objects:
            sec, key = mapping.get(o, ("?", "?"))
            synced = sorted({f"{spc}:{sn}/{k}" for spc, s in spcs.items() for sn, k, ob in s["produced"] if ob == o})
            print(f"{o}\t<- canonical {sec}/{key}\t-> {', '.join(synced)}")

    return errors, spcs, consumers, all_objects


def main(argv):
    if len(argv) < 3 or argv[1] not in ("check", "objects", "access"):
        sys.stderr.write(__doc__.split("\n\n")[1] + "\n")
        return 2
    verbose = "--verbose" in argv[3:]
    errors, spcs, consumers, objects = check(load(argv[2]), verbose=verbose and argv[1] == "check")
    if errors:
        for e in errors:
            sys.stderr.write(f"FAIL: {e}\n")
        sys.stderr.write(f"FAIL: {len(errors)} break(s) in the Key Vault chain\n")
        return 1
    if argv[1] == "objects":
        for o in objects:
            print(o)
        return 0
    if argv[1] == "access":
        for cid, o in sorted({(s["clientID"], o) for s in spcs.values() for o in s["objects"]}):
            print(f"{cid}\t{o}")
        return 0
    print(f"OK: {len(spcs)} SecretProviderClasses, {len(consumers)} consumed (Secret, key) reference(s), "
          f"{len(objects)} Key Vault objects; every consumer mounts its one producer, every clientID is its "
          f"ServiceAccount's")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
