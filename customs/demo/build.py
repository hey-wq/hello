"""Assemble the single-file demo page from the real review screen.

    python demo/build.py            writes demo/customs-desk-demo.html

The page is the real app.css and app.js with demo/backend.js standing in for
the server, plus the sample shipment and sample reference values.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WEB = ROOT / "customsdesk" / "web"
OUT = ROOT / "demo" / "customs-desk-demo.html"


def demo_data() -> dict:
    with open(ROOT / "data" / "benchmarks_sample.csv", newline="", encoding="utf-8") as fh:
        benchmarks = [
            {
                "code": r["code"],
                "origin": r["origin"],
                "median": float(r["median_eur_per_kg"]),
                "low": float(r["low_eur_per_kg"]),
                "source": r["source"],
                "period": r["period"],
            }
            for r in csv.DictReader(fh)
        ]
    sample = json.loads((ROOT / "samples" / "sample_shipment.json").read_text(encoding="utf-8"))
    return {"sample": sample, "benchmarks": benchmarks}


def build() -> str:
    data = json.dumps(demo_data(), ensure_ascii=False).replace("</", "<\\/")
    return f"""<title>Customs Desk Demo</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
{(WEB / "app.css").read_text(encoding="utf-8")}
</style>

<header class="bar">
  <span class="brand">Customs desk</span>
  <div class="who" id="who"></div>
</header>
<main>
  <div class="demo-note">Demo on a made-up shipment. Nothing is saved or sent, and reloading starts again.
  Try it: enter an exchange rate (1.08), freight (2800) and an EORI (GR000000000), complete the two short
  commodity codes to 10 digits, then resolve the flags, confirm the codes and approve.</div>
  <div id="app"></div>
</main>
<div id="toast" role="status" aria-live="polite" hidden></div>

<script>
window.DEMO = true;
window.DEMO_DATA = {data};
</script>
<script>
{(ROOT / "demo" / "backend.js").read_text(encoding="utf-8")}
</script>
<script>
{(WEB / "app.js").read_text(encoding="utf-8")}
</script>
"""


if __name__ == "__main__":
    OUT.write_text(build(), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
