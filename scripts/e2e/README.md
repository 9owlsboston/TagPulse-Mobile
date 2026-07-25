# E2E fixtures (HIL)

Hardware-/backend-in-the-loop fixtures for the OBD-II MVE
(`docs/design/obdii-mve-plan.md`). These need a **live TagPulse backend** and are
therefore **not** part of the offline unit gate — they are run manually against a
dev tenant.

## `a7-map-check.py` — acceptance criterion **A7**

Proves that a relayed OBD-II read surfaces the **vehicle asset on the Map** at the
read's GPS location (plan §1 A7, §5 Fix 3). It seeds a vehicle asset + a
**`binding_kind='device'`** binding (whose `binding_value` = the `tag_id` the phone
reports), relays a `POST /tag-reads/batch` exactly as the app does, then asserts
`GET /assets/current-locations` returns the vehicle at that location.

### Why HIL / not run in CI here

The backend runs under Docker (`docker compose up` in `~/ws/TagPulse`), and **Docker
is not available in this WSL distro** — so this script is authored and its
endpoints/fields **validated against the `~/ws/TagPulse` source** (`main` @ `06dde2b`),
but it is **not executed here**. Run it yourself per the steps below. It uses only the
Python **standard library** (no `pip install`).

### Prerequisites

1. **A running backend.** In `~/ws/TagPulse`:
   ```bash
   docker compose up            # serves the API on http://localhost:8000
   ```
   (Or point `--base-url` at any deployed dev environment.)
2. **A tenant user API key** (`tp_{slug}_…`) whose user has **admin/editor** role —
   needed to create the category/asset/binding. Issued out-of-band by a tenant admin
   (`POST /users/{id}/api-key`, revealed once). This is the same ingest credential the
   handset uses (plan §5, Fix 1).

### Run

```bash
python3 scripts/e2e/a7-map-check.py \
  --base-url http://localhost:8000 \
  --api-key  tp_acme_XXXXXXXXXXXXXXXXXXXX
```

It prints each step and finishes with `A7 PASS` (exit 0) or `A7 FAIL: …` (exit ≠ 0).

Useful flags:

| Flag | Purpose |
|---|---|
| `--tag-id` | Pin the reported tag id (default: random `obdii-a7-…`). |
| `--category-id` | Reuse an existing category instead of creating one. |
| `--device-id` | Reuse an existing device UUID. |
| `--provision` + `--provisioning-key` | Provision a device via `X-Provisioning-Key` instead of `POST /device-registry`. |
| `--lat` / `--lon` / `--accuracy-m` | The read's GPS fix (default: Boston City Hall). |

> **Note on `device_id`:** ingest authenticates with the **tenant key**, not a device
> token (plan §5 🚩), and the current-locations link is keyed on `tag_id ↔ device`-binding
> (not on `device_id`), so any valid device UUID works. The script registers a real
> device by default so the read has a genuine `device_id`.
