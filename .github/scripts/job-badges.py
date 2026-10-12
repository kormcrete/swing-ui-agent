#!/usr/bin/env python3
"""Turns a workflow run's jobs (the GitHub API 'jobs' JSON, first argument) into two shields.io endpoint badges,
written to the folder in the second argument: java.json (a Java version passes when every '<OS> / Java N' job for
it succeeded) and platform.json (an OS passes when every one of its jobs succeeded)."""
import json
import os
import re
import sys

with open(sys.argv[1]) as f:
    jobs = json.load(f)["jobs"]

PLATFORMS = ["macOS", "Linux", "Windows"]
by_java, by_platform = {}, {}
for job in jobs:
    m = re.fullmatch(r"(macOS|Linux|Windows) / Java (\d+)", job["name"])
    if m:
        ok = job["conclusion"] == "success"
        by_platform[m.group(1)] = by_platform.get(m.group(1), True) and ok
        by_java[int(m.group(2))] = by_java.get(int(m.group(2)), True) and ok


def badge(label, results, order):
    keys = [k for k in order if k in results]
    passing = [str(k) for k in keys if results[k]]
    failing = [str(k) for k in keys if not results[k]]
    if not keys:
        message, color = "unknown", "lightgrey"
    elif not passing:
        message, color = "failing", "red"
    elif failing:
        message, color = "%s (%s failing)" % (" | ".join(passing), ", ".join(failing)), "yellow"
    else:
        message, color = " | ".join(passing), "brightgreen"
    return {"schemaVersion": 1, "label": label, "message": message, "color": color}


out = sys.argv[2]
with open(os.path.join(out, "java.json"), "w") as f:
    json.dump(badge("Java", by_java, sorted(by_java)), f)
with open(os.path.join(out, "platform.json"), "w") as f:
    json.dump(badge("platform", by_platform, PLATFORMS), f)
