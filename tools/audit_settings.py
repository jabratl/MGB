#!/usr/bin/env python3
"""STEP E audit: lists suspicious rows in SettingsScreen.kt. It only LISTS, it never edits.
Run from the repo root:  python3 tools/audit_settings.py
Checks: (a) same row label twice, (b) rows whose click handler writes the same prefs key,
(c) single switches that DlMode.manual() moves to Manual (overlap with Button mode / V77 backup),
(d) rows without any handler (dead)."""
import re, sys, collections
P = "app/src/main/java/com/jabra/mgb/SettingsScreen.kt"
src = open(P, encoding="utf-8").read().split("\n")
cat = "?"
rows = []  # (line, cat, label, body)
i = 0
while i < len(src):
    ln = src[i]
    m = re.search(r'category\("(\w+)"', ln)
    if m: cat = m.group(1)
    m = re.search(r'Row\(R\.drawable\.\w+,\s*"([^"]+)"', ln)
    if m:
        depth = 0; body = []
        j = i
        while j < len(src) and j < i + 25:
            body.append(src[j]); depth += src[j].count("{") - src[j].count("}")
            if j > i and depth <= 0: break
            j += 1
        rows.append((i + 1, cat, m.group(1), "\n".join(body)))
    i += 1
print("Rows found:", len(rows))
print("\n(a) duplicate labels")
cnt = collections.defaultdict(list)
for l, c, lab, b in rows: cnt[lab].append((l, c))
for lab, v in cnt.items():
    if len(v) > 1: print("  ", lab, v)
print("\n(b) same prefs key written by more than one row")
keys = collections.defaultdict(list)
for l, c, lab, b in rows:
    for k in set(re.findall(r'(?:putBoolean|putInt|putString|TabPrefs\.set)\(\s*(?:act,\s*)?"(\w+)"', b)): keys[k].append((l, lab))
for k, v in keys.items():
    if len(v) > 1: print("  ", k, v)
print("\n(c) single switches that set Manual mode (DlMode.manual)")
for l, c, lab, b in rows:
    if "DlMode.manual" in b: print("  line", l, c, lab)
print("\n(d) rows without a click handler")
for l, c, lab, b in rows:
    if "{" not in b.split("\n")[0] and "{" not in b: print("  line", l, c, lab)
