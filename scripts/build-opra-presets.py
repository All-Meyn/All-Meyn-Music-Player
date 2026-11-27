#!/usr/bin/env python3
"""
Builds the headphone EQ presets both apps bundle (app/src/main/assets/opra/presets.tsv.gz) from
OPRA's database (https://github.com/opra-project/OPRA, data under CC BY-SA 4.0).

    git clone --depth 1 https://github.com/opra-project/OPRA.git /tmp/OPRA
    python3 scripts/build-opra-presets.py /tmp/OPRA

One line per EQ preset, tab separated (read by OpraPresets.kt):
    vendor  product  kind  author  details  preamp  bands
bands: "TYPE Hz dB Q" joined by ";" with EqualizerAPO type names (PK LSC HSC LPQ HPQ).
Lines starting with "#" are comments (the source commit and the license).
Presets with filters the engine doesn't have (band pass / band stop, or low/high pass steeper or
shallower than 12 dB/octave) are left out and counted.
"""
import gzip
import json
import os
import subprocess
import sys

KINDS = {"in_ear": "In-ear", "over_the_ear": "Over-ear", "on_ear": "On-ear", "earbuds": "Earbuds"}
SHAPES = {"peak_dip": "PK", "low_shelf": "LSC", "high_shelf": "HSC"}
PASSES = {"low_pass": "LPQ", "high_pass": "HPQ"}


def clean(s):
    return " ".join(str(s or "").replace("\t", " ").split())


def num(v):
    return ("%.3f" % float(v)).rstrip("0").rstrip(".")


def main(repo, out):
    commit = subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip() or "unknown"
    vendors, products, eqs = {}, {}, []
    with open(os.path.join(repo, "dist", "database_v1.jsonl"), encoding="utf-8") as f:
        for line in f:
            o = json.loads(line)
            d = o["data"]
            if o["type"] == "vendor":
                vendors[o["id"]] = clean(d["name"])
            elif o["type"] == "product":
                products[o["id"]] = d
            elif o["type"] == "eq" and d.get("type") == "parametric_eq":
                eqs.append(d)

    rows, skipped = [], 0
    for d in eqs:
        p = products.get(d["product_id"])
        if p is None:
            skipped += 1
            continue
        bands = []
        for b in d["parameters"].get("bands", []):
            t = b["type"]
            if t in SHAPES:
                bands.append("%s %s %s %s" % (SHAPES[t], num(b["frequency"]), num(b.get("gain_db", 0)), num(b["q"])))
            elif t in PASSES and b.get("slope", 12) == 12:
                bands.append("%s %s 0 0.707" % (PASSES[t], num(b["frequency"])))  # 12 dB/oct = Butterworth Q
            else:
                bands = None
                break
        if not bands or len(bands) > 20:
            skipped += 1
            continue
        rows.append("\t".join([
            vendors.get(p["vendor_id"], p["vendor_id"]), clean(p["name"]), KINDS.get(p.get("subtype"), ""),
            clean(d.get("author")), clean(d.get("details")), num(d["parameters"].get("gain_db", 0)), ";".join(bands),
        ]))
    rows.sort(key=lambda r: r.lower())

    with gzip.GzipFile(out, "wb", mtime=0) as g:  # mtime 0: the same input gives the same bytes
        head = (
            "# OPRA headphone EQ presets, https://github.com/opra-project/OPRA (commit %s)\n"
            "# Data: CC BY-SA 4.0, https://creativecommons.org/licenses/by-sa/4.0/ . Each preset's creator is in column 4.\n"
            "# Converted by scripts/build-opra-presets.py (filter types renamed to EqualizerAPO's; unsupported presets left out).\n"
        ) % commit
        g.write((head + "\n".join(rows) + "\n").encode("utf-8"))
    print("%d presets for %d headphones written to %s (%d bytes); %d left out" % (
        len(rows), len({r.split("\t")[0] + "\t" + r.split("\t")[1] for r in rows}), out, os.path.getsize(out), skipped))


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    main(sys.argv[1], os.path.join(here, "..", "app", "src", "main", "assets", "opra", "presets.tsv.gz"))
