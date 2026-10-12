#!/usr/bin/env python3
"""Sums the surefire XML reports in a folder into a shields.io endpoint badge (JSON on stdout)."""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET

folder = sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports"
tests = failed = skipped = 0
for path in glob.glob(os.path.join(folder, "TEST-*.xml")):
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests", 0))
    failed += int(suite.get("failures", 0)) + int(suite.get("errors", 0))
    skipped += int(suite.get("skipped", 0))

if tests == 0:
    message, color = "no results", "lightgrey"
elif failed:
    message, color = "%d failed, %d passed" % (failed, tests - failed - skipped), "red"
else:
    message, color = "%d passed" % (tests - skipped), "brightgreen"
    if skipped:
        message += ", %d skipped" % skipped

print(json.dumps({"schemaVersion": 1, "label": "tests", "message": message, "color": color}))
