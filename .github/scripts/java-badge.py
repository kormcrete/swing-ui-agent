#!/usr/bin/env python3
"""Turns a workflow run's jobs (the GitHub API 'jobs' JSON, file argument) into the Java versions badge: the
'macOS / Java N' jobs that succeeded, as a shields.io endpoint badge (JSON on stdout)."""
import json
import re
import sys

with open(sys.argv[1]) as f:
    jobs = json.load(f)["jobs"]

results = {}
for job in jobs:
    m = re.fullmatch(r"macOS / Java (\d+)", job["name"])
    if m:
        results[int(m.group(1))] = job["conclusion"] == "success"

passing = [str(v) for v in sorted(results) if results[v]]
failing = [str(v) for v in sorted(results) if not results[v]]
if not results:
    message, color = "unknown", "lightgrey"
elif not passing:
    message, color = "failing", "red"
elif failing:
    message, color = "%s (%s failing)" % (" | ".join(passing), ", ".join(failing)), "yellow"
else:
    message, color = " | ".join(passing), "brightgreen"

print(json.dumps({"schemaVersion": 1, "label": "Java", "message": message, "color": color}))
