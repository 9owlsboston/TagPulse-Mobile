#!/usr/bin/env python3
"""A7 end-to-end fixture — vehicle asset link on the Map (OBD-II MVE).

Proves acceptance criterion **A7** of ``docs/design/obdii-mve-plan.md`` (§1 A7, §5
Fix 3): after a relayed OBD-II read, the vehicle appears in the current-locations
view **at the read's location** — which only happens when the vehicle carries an
``asset_tag_bindings`` row with ``binding_kind='device'`` whose ``binding_value``
equals the ``tag_id`` the phone reports (plan §4, §5).

WHAT IT DOES (against a running dev tenant):
  1. Resolve/create an "object" **category** (assets require a category_id).
  2. Create a **vehicle asset**.
  3. Bind a **binding_kind='device'** row: binding_value = the tag_id the phone reports.
  4. Resolve a **device_id** (register one, or use --device-id / provision).
  5. POST **/tag-reads/batch** exactly as the app would — device_id + tag_id +
     sensor_data (the PID snapshot) + location {latitude, longitude, accuracy_m,
     source:"gps"}; assert 201 {ingested:1, rejected:0}.
  6. GET **/assets/current-locations**; assert the vehicle is present at the read's
     location (kind == "geo", lat/lon match within tolerance).

  On success prints "A7 PASS" and exits 0; any failed assertion exits non-zero.

  ┌─ HIL / not-run-here ────────────────────────────────────────────────────┐
  │ This needs a LIVE TagPulse backend (docker compose up, or a deployed dev  │
  │ env) + a tenant user API key. Docker is unavailable in this WSL distro,   │
  │ so this script is authored + endpoint/field-validated against ~/ws/TagPulse│
  │ but is NOT executed here. See scripts/e2e/README.md for run steps.         │
  └───────────────────────────────────────────────────────────────────────────┘

AUTH (plan §5, Fix 1): all tenant calls use ``Authorization: ****** with an
out-of-band tenant user API key (``tp_{slug}_…``) whose user has admin/editor role
(needed to create the category/asset/binding). Provisioning uses X-Provisioning-Key.

Endpoints/fields verified against ~/ws/TagPulse @ main (06dde2b):
  - POST /categories            {name, category_type}         -> {id}         (categories.py:82; CategoryCreate)
  - POST /assets                {name, category_id}           -> {id}         (assets.py:43; AssetCreate)
  - POST /assets/{id}/bindings  {binding_value, binding_kind} -> 201          (assets.py:243; AssetTagBindingCreate, kind enum incl. "device")
  - POST /device-registry       {name, device_type}          -> {id}         (devices.py:27; DeviceCreate)
  - POST /devices/provision     {name, device_type} + X-Provisioning-Key      (provisioning.py:35)
  - POST /tag-reads/batch       [TagReadCreate]  -> 201 {ingested, rejected}  (ingestion.py:38)
  - GET  /assets/current-locations             -> [AssetCurrentLocation]      (assets.py:129; latitude/longitude/kind)
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


class Backend:
    """Minimal stdlib HTTP client for the TagPulse API (no third-party deps)."""

    def __init__(self, base_url: str, api_key: str, provisioning_key: str | None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.provisioning_key = provisioning_key

    def _request(self, method: str, path: str, body: dict | list | None = None,
                 provisioning: bool = False) -> tuple[int, dict | list | None]:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if provisioning:
            if not self.provisioning_key:
                fail("provisioning requested but --provisioning-key not supplied")
            req.add_header("X-Provisioning-Key", self.provisioning_key)
        else:
            req.add_header("Authorization", f"Bearer {self.api_key}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                payload = resp.read().decode() or "null"
                return resp.status, json.loads(payload)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")
            fail(f"{method} {path} -> HTTP {e.code}: {detail}")
        except urllib.error.URLError as e:
            fail(f"{method} {path} -> connection error: {e.reason} "
                 f"(is the backend up at {self.base_url}?)")
        return 0, None  # unreachable (fail raises)

    def post(self, path, body, provisioning=False):
        return self._request("POST", path, body, provisioning)

    def get(self, path):
        return self._request("GET", path)


def fail(msg: str) -> None:
    print(f"A7 FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def resolve_category(api: Backend, category_id: str | None) -> str:
    if category_id:
        print(f"  · using provided category_id={category_id}")
        return category_id
    name = f"a7-vehicle-cat-{uuid.uuid4().hex[:8]}"
    # category_type "object" fits a vehicle (Literal in schemas.py: liquid_container|
    # reference_tag|rti_container|object).
    status, resp = api.post("/categories", {"name": name, "category_type": "object"})
    if status != 201 or "id" not in resp:
        fail(f"category create returned {status}: {resp}")
    print(f"  · created category id={resp['id']}")
    return resp["id"]


def create_asset(api: Backend, category_id: str) -> str:
    name = f"a7-vehicle-{uuid.uuid4().hex[:8]}"
    status, resp = api.post("/assets", {"name": name, "category_id": category_id})
    if status != 201 or "id" not in resp:
        fail(f"asset create returned {status}: {resp}")
    print(f"  · created asset id={resp['id']} name={name}")
    return resp["id"]


def bind_device_tag(api: Backend, asset_id: str, tag_id: str) -> None:
    # Fix 3: the vehicle MUST carry a device-kind binding matching tr.tag_id, else it
    # never surfaces on current-locations (an EPC/TID binding would need identity.*).
    status, resp = api.post(
        f"/assets/{asset_id}/bindings",
        {"binding_value": tag_id, "binding_kind": "device"},
    )
    if status != 201:
        fail(f"device-binding create returned {status}: {resp}")
    print(f"  · bound binding_kind='device' binding_value={tag_id} -> asset {asset_id}")


def resolve_device_id(api: Backend, device_id: str | None, provision: bool) -> str:
    if device_id:
        print(f"  · using provided device_id={device_id}")
        return device_id
    if provision:
        status, resp = api.post(
            "/devices/provision",
            {"name": f"a7-phone-{uuid.uuid4().hex[:8]}", "device_type": "mobile_gateway"},
            provisioning=True,
        )
        if status != 201 or "device_id" not in resp:
            fail(f"provision returned {status}: {resp}")
        print(f"  · provisioned device_id={resp['device_id']} (status=pending; "
              "ingest works via the tenant key regardless)")
        return resp["device_id"]
    status, resp = api.post(
        "/device-registry",
        {"name": f"a7-phone-{uuid.uuid4().hex[:8]}", "device_type": "mobile_gateway"},
    )
    if status != 201 or "id" not in resp:
        fail(f"device-registry create returned {status}: {resp}")
    print(f"  · registered device_id={resp['id']}")
    return resp["id"]


def relay_read(api: Backend, device_id: str, tag_id: str, lat: float, lon: float,
               accuracy_m: float) -> None:
    # The exact TagReadCreate the app relays (plan §4): device_id + tag_id +
    # sensor_data (the OBD PID snapshot) + location{...,source:"gps"}.
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    tag_read = {
        "device_id": device_id,
        "tag_id": tag_id,
        "timestamp": now,
        "sensor_data": {
            "modality": "obdii",
            "protocol": "elm327/j1979",
            "captured_at": now,
            "pids": {"rpm": 850, "speed_kph": 0, "coolant_temp_c": 89, "fuel_level_pct": 47.5},
        },
        "location": {
            "latitude": lat,
            "longitude": lon,
            "accuracy_m": accuracy_m,
            "source": "gps",
        },
    }
    status, resp = api.post("/tag-reads/batch", [tag_read])
    if status != 201:
        fail(f"tag-reads/batch returned {status}: {resp}")
    if not isinstance(resp, dict) or resp.get("ingested") != 1 or resp.get("rejected") != 0:
        fail(f"expected {{ingested:1, rejected:0}}, got {resp}")
    print(f"  · relayed read -> 201 {resp}")


def assert_on_map(api: Backend, asset_id: str, lat: float, lon: float,
                  tol: float = 1e-4) -> None:
    status, rows = api.get("/assets/current-locations?limit=1000")
    if status != 200 or not isinstance(rows, list):
        fail(f"current-locations returned {status}: {rows}")
    row = next((r for r in rows if str(r.get("asset_id")) == str(asset_id)), None)
    if row is None:
        fail(f"asset {asset_id} NOT in current-locations (device binding not resolving?)")
    if row.get("kind") != "geo":
        fail(f"asset present but kind={row.get('kind')} (expected 'geo' from the GPS read): {row}")
    got_lat, got_lon = row.get("latitude"), row.get("longitude")
    if got_lat is None or got_lon is None:
        fail(f"asset present but no lat/lon: {row}")
    if abs(got_lat - lat) > tol or abs(got_lon - lon) > tol:
        fail(f"location mismatch: read ({lat},{lon}) vs current-locations ({got_lat},{got_lon})")
    print(f"  · current-locations shows asset {asset_id} at ({got_lat},{got_lon}) — MATCH")


def main() -> None:
    p = argparse.ArgumentParser(description="A7 E2E: OBD-II read -> vehicle on the Map.")
    p.add_argument("--base-url", required=True, help="e.g. http://localhost:8000")
    p.add_argument("--api-key", required=True, help="tenant user API key (tp_{slug}_…, admin/editor)")
    p.add_argument("--tag-id", default=None, help="the tag_id the phone reports (default: random)")
    p.add_argument("--category-id", default=None, help="reuse an existing category instead of creating one")
    p.add_argument("--device-id", default=None, help="reuse an existing device UUID")
    p.add_argument("--provisioning-key", default=None, help="tenant provisioning key (to provision a device)")
    p.add_argument("--provision", action="store_true", help="provision a device via X-Provisioning-Key")
    p.add_argument("--lat", type=float, default=42.3601)
    p.add_argument("--lon", type=float, default=-71.0589)
    p.add_argument("--accuracy-m", type=float, default=4.5)
    args = p.parse_args()

    tag_id = args.tag_id or f"obdii-a7-{uuid.uuid4().hex[:12]}"
    api = Backend(args.base_url, args.api_key, args.provisioning_key)

    print("A7 fixture — OBD-II read -> vehicle on the Map")
    print(f"  base_url={api.base_url} tag_id={tag_id}")
    category_id = resolve_category(api, args.category_id)
    asset_id = create_asset(api, category_id)
    bind_device_tag(api, asset_id, tag_id)
    device_id = resolve_device_id(api, args.device_id, args.provision)
    relay_read(api, device_id, tag_id, args.lat, args.lon, args.accuracy_m)
    assert_on_map(api, asset_id, args.lat, args.lon)

    print("A7 PASS: the relayed OBD-II read surfaced the vehicle on the Map at its GPS location.")


if __name__ == "__main__":
    main()
