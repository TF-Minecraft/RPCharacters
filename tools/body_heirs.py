#!/usr/bin/env python3
"""Propose the character body heir manifest from RPCharacters data.

Reads plugins/RPCharacters/data (playerdata, characterdata, playtime-index.json)
and writes the manifest, its SHA-256 and a review report. Each account's heir is
its active living character, else its most-played living character, else its
most-played character. Staff decisions in --overrides win over that rule.
Accounts that need a decision are listed in the report.

Usage:
    body_heirs.py DATA_DIR OUT_DIR [--overrides reviewed.yml]
"""

import argparse
import hashlib
import json
import re
import sys
import uuid
from pathlib import Path

ENTRY = re.compile(r"^\s+([0-9a-fA-F-]{36})\s*:\s*['\"]?([0-9a-fA-F-]{36})['\"]?\s*(#.*)?$")


def parse_uuid(value):
    try:
        return str(uuid.UUID(str(value)))
    except ValueError:
        return None


def read_overrides(path):
    overrides = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or stripped in ("version: 1", "heirs:"):
            continue
        match = ENTRY.match(line)
        if not match or not parse_uuid(match[1]) or not parse_uuid(match[2]):
            sys.exit(f"{path}:{number}: expected '  <account uuid>: <character uuid>'")
        overrides[parse_uuid(match[1])] = parse_uuid(match[2])
    return overrides


def load(data):
    names = {}
    index = data / "playtime-index.json"
    if index.exists():
        for row in json.loads(index.read_text(encoding="utf-8")):
            if parse_uuid(row.get("uuid")):
                names[parse_uuid(row["uuid"])] = row.get("name", "")

    accounts, problems = {}, []
    owners = {p.stem for p in (data / "playerdata").glob("*.json")}
    owners |= {p.name for p in (data / "characterdata").iterdir() if p.is_dir()}
    for raw in sorted(owners):
        owner = parse_uuid(raw)
        if owner is None or owner != raw:
            problems.append(("not-a-uuid", raw, "", f"account folder or file '{raw}' is not a lower-case UUID"))
            continue
        characters = []
        folder = data / "characterdata" / raw
        for file in sorted(folder.glob("*.json")) if folder.is_dir() else []:
            try:
                record = json.loads(file.read_text(encoding="utf-8"))
                if not isinstance(record, dict):
                    raise ValueError("not a JSON object")
                seconds = max(0, int(record.get("online-playtime-seconds") or 0))
            except (OSError, ValueError, TypeError) as error:
                problems.append(("unreadable", owner, file.name, str(error)))
                continue
            cid = parse_uuid(record.get("id"))
            if cid is None or cid != record.get("id") or file.stem != cid:
                problems.append(("bad-character-id", owner, file.name, f"id {record.get('id')!r}"))
                continue
            characters.append({
                "id": cid,
                "name": record.get("name", ""),
                "status": str(record.get("status", "")).upper(),
                "seconds": seconds,
                "active": str(record.get("active")).lower() == "true",
            })
        accounts[owner] = {"name": names.get(owner, ""), "characters": characters}
    return accounts, problems


def propose(accounts, problems, overrides):
    seen = {}
    for owner, account in accounts.items():
        for c in account["characters"]:
            if c["id"] in seen and seen[c["id"]] != owner:
                problems.append(("duplicate-character-id", owner, c["id"], f"also under account {seen[c['id']]}"))
            seen.setdefault(c["id"], owner)
    blocked = {p[1] for p in problems if p[0] == "duplicate-character-id"}
    blocked |= {seen[p[2]] for p in problems if p[0] == "duplicate-character-id"}

    heirs, review = {}, []
    for owner in sorted(set(overrides) - {o for o, a in accounts.items() if a["characters"]}):
        problems.append(("override-not-found", owner, overrides[owner], "account has no characters"))
    for owner, account in sorted(accounts.items()):
        characters = account["characters"]
        if not characters or owner in blocked:
            continue
        by_id = {c["id"]: c for c in characters}
        living = [c for c in characters if c["status"] == "ALIVE"]
        active = [c for c in living if c["active"]]
        ranked = sorted(active or characters, key=lambda c: (c["status"] != "ALIVE", -c["seconds"], c["id"]))
        if owner in overrides:
            if overrides[owner] not in by_id:
                problems.append(("override-not-found", owner, overrides[owner], "reviewed heir is not on this account"))
                continue
            heirs[owner] = overrides[owner]
            continue
        heir = ranked[0]
        heirs[owner] = heir["id"]
        if len(active) > 1:
            review.append((owner, "several living characters are marked active"))
        elif active:
            continue
        elif not living:
            review.append((owner, "no living character; heir is the most-played dead character"))
        elif any(c["active"] for c in characters):
            review.append((owner, "the active character is dead; heir is the most-played living character"))
        elif sum(c["seconds"] == heir["seconds"] for c in living) > 1:
            review.append((owner, "no active character and living characters tie on playtime"))
    return heirs, review


def write(out, accounts, problems, heirs, review):
    out.mkdir(parents=True, exist_ok=True)
    lines = ["version: 1", "heirs:"] + [f"  {o}: {h}" for o, h in sorted(heirs.items())]
    manifest = ("\n".join(lines) + "\n").encode("utf-8")
    (out / "character-body-heirs.yml").write_bytes(manifest)
    digest = hashlib.sha256(manifest).hexdigest()
    (out / "character-body-heirs.sha256").write_text(digest + "\n", encoding="utf-8")

    def describe(owner):
        account = accounts.get(owner, {"name": "", "characters": []})
        parts = []
        for c in sorted(account["characters"], key=lambda c: -c["seconds"]):
            mark = " (heir)" if heirs.get(owner) == c["id"] else ""
            active = ", active" if c["active"] else ""
            parts.append(f"{c['name']} {c['id']} {c['status'].lower()} {c['seconds'] // 3600} h{active}{mark}")
        return f"{account['name'] or '?'} {owner}: " + "; ".join(parts)

    multi = sum(len(a["characters"]) > 1 for a in accounts.values())
    empty = sorted(o for o, a in accounts.items() if not a["characters"])
    report = [
        "# Character body heirs",
        "",
        f"SHA-256: `{digest}`",
        "",
        f"{len(accounts)} accounts, {sum(len(a['characters']) for a in accounts.values())} characters, "
        f"{len(heirs)} heirs, {multi} accounts with more than one character.",
        "",
        "## Needs a staff decision",
        "",
    ]
    report += [f"- {reason}: {describe(owner)}" for owner, reason in review] or ["None."]
    report += ["", "## Heir is not the most-played character", ""]
    report += [f"- {describe(o)}" for o, a in sorted(accounts.items(), key=lambda x: x[1]["name"].lower())
               if o in heirs and max(a["characters"], key=lambda c: c["seconds"])["id"] != heirs[o]] or ["None."]
    report += ["", "## Blocked until fixed", ""]
    report += [f"- {kind}: account {owner} {detail} {note}".rstrip() for kind, owner, detail, note in problems] or ["None."]
    report += ["", "## Accounts with no characters", "",
               "These get no heir. Their current inventory is kept in quarantine at cutover.", ""]
    report += [f"- {accounts[o]['name'] or '?'} {o}" for o in empty] or ["None."]
    report += ["", "## Accounts with more than one character", ""]
    report += [f"- {describe(o)}" for o, a in sorted(accounts.items(), key=lambda x: x[1]["name"].lower())
               if len(a["characters"]) > 1] or ["None."]
    (out / "character-body-heirs-review.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    return digest


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("data", type=Path, help="plugins/RPCharacters/data")
    parser.add_argument("out", type=Path, help="folder for the manifest, checksum and report")
    parser.add_argument("--overrides", type=Path, help="reviewed heirs that replace the proposal")
    args = parser.parse_args()
    accounts, problems = load(args.data)
    overrides = read_overrides(args.overrides) if args.overrides else {}
    heirs, review = propose(accounts, problems, overrides)
    digest = write(args.out, accounts, problems, heirs, review)
    print(f"{len(heirs)} heirs, {len(review)} to review, {len(problems)} blocked; sha256 {digest}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
