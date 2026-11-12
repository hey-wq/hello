"""Check that the demo's in-browser backend agrees with the real server.

    python demo/parity.py

Runs the same review steps through both and compares flags and values.
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "demo"))

from build import demo_data  # noqa: E402
from customsdesk.api import create_app  # noqa: E402

ME = {"actor": "Parity"}
NOTE = "Importer sent the bank transfer record matching the invoice."
STEPS = [
    ("POST", "/api/sample", ME),
    ("PATCH", "/api/shipments/{id}", ME | {"header": {"exchange_rate": 1.08}}),
    ("PATCH", "/api/shipments/{id}", ME | {"header": {"freight": 2800}, "importer": {"eori": "GR000000000"}}),
    ("PATCH", "/api/shipments/{id}", ME | {"items": {"1": {"commodity_code": "6203423100"}, "2": {"commodity_code": "64041100 00"}}}),
    ("POST", "/api/shipments/{id}/approve", ME),
    ("POST", "/api/shipments/{id}/flags/value_below_reference:1/resolve", ME | {"resolution": "importer_confirmed", "note": NOTE}),
    ("PATCH", "/api/shipments/{id}", ME | {"header": {"freight": 100}}),
    ("PATCH", "/api/shipments/{id}", ME | {"header": {"freight": 2800}, "items": {"0": {"commodity_code": "6109100090"}}}),
    ("POST", "/api/shipments/{id}/flags/value_below_reference:1/resolve", ME | {"resolution": "importer_confirmed", "note": NOTE}),
    ("POST", "/api/shipments/{id}/flags/new_importer_low_value/resolve", ME | {"resolution": "guarantee_taken", "note": NOTE}),
    ("POST", "/api/shipments/{id}/flags/quantity_mismatch:1/resolve", ME | {"resolution": "importer_confirmed", "note": "ok"}),
    ("POST", "/api/shipments/{id}/flags/quantity_mismatch:1/resolve", ME | {"resolution": "importer_confirmed", "note": NOTE}),
    ("POST", "/api/shipments/{id}/items/0/confirm", ME),
    ("POST", "/api/shipments/{id}/items/1/confirm", ME),
    ("POST", "/api/shipments/{id}/items/2/confirm", ME),
    ("POST", "/api/shipments/{id}/approve", ME),
    ("PATCH", "/api/shipments/{id}", ME | {"header": {"freight": 1}}),
    ("POST", "/api/sample", ME),  # second shipment: codes from history, importer now known
]

NODE = """
globalThis.window = globalThis;
window.DEMO_DATA = JSON.parse(process.argv[1]);
require(process.argv[2]);
(async () => {
  const out = []; let id = null;
  for (const [method, path, body] of JSON.parse(process.argv[3])) {
    const r = await fetch(path.replace('{id}', id), { method, body: JSON.stringify(body) });
    const data = await r.json();
    if (path === '/api/sample') id = data.shipment.id;
    out.push({ status: r.status, data });
  }
  console.log(JSON.stringify(out));
})();
"""


def essence(status, data):
    if status != 200:
        return {"status": status, "detail": data["detail"]}
    s = data["shipment"]
    return {
        "status": s["status"],
        "blocking": data["blocking_flags"],
        "unconfirmed": data["unconfirmed_items"],
        "flags": sorted(
            [f["id"], f["severity"], f["title"], f["detail"], f["evidence"], f["resolution"], f["resolution_note"]]
            for f in s["flags"]
        ),
        "items": [
            [i["commodity_code"], i["code_source"], i["code_confirmed"], i["customs_value_eur"], i["code_confidence"]]
            for i in s["declaration"]["items"]
        ],
        "events": [[e["type"], e["payload"]] for e in data["events"]],
    }


def main():
    with tempfile.TemporaryDirectory() as tmp:
        client = TestClient(create_app(data_dir=tmp))
        real, sid = [], None
        for method, path, body in STEPS:
            r = client.request(method, path.replace("{id}", str(sid)), json=body)
            if path == "/api/sample":
                sid = r.json()["shipment"]["id"]
            real.append(essence(r.status_code, r.json()))

    done = subprocess.run(
        ["node", "-e", NODE, json.dumps(demo_data()), str(ROOT / "demo" / "backend.js"), json.dumps(STEPS)],
        capture_output=True, text=True,
    )
    if done.returncode:
        sys.exit("demo backend failed to run:\n" + done.stderr[-2000:])
    demo = [essence(x["status"], x["data"]) for x in json.loads(done.stdout)]

    failures = 0
    for n, (a, b) in enumerate(zip(real, demo)):
        # The real record also logs the model that read the documents; the demo has no reader.
        for side in (a, b):
            for _, payload in side.get("events", []):
                payload.pop("legibility", None)
                payload.pop("read_by", None)
        if a != b:
            failures += 1
            print(f"step {n} {STEPS[n][0]} {STEPS[n][1]} differs")
            for key in a.keys() | b.keys():
                if a.get(key) != b.get(key):
                    print(f"  {key}:\n    server: {json.dumps(a.get(key), ensure_ascii=False)[:600]}\n    demo:   {json.dumps(b.get(key), ensure_ascii=False)[:600]}")
    print(f"{len(STEPS) - failures}/{len(STEPS)} steps agree")
    return failures


if __name__ == "__main__":
    sys.exit(1 if main() else 0)
