"""Prints proxy/build/results/jmh/results.json as a table (decision step, microseconds)."""
import json
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "proxy/build/results/jmh/results.json"
print(f"{'query':<14}{'shape cache':<13}{'mean':>9}{'p50':>9}{'p99':>9}{'p99.9':>9}   (µs)")
for r in json.load(open(path)):
    p, m = r["params"], r["primaryMetric"]
    pc = m["scorePercentiles"]
    cache = "off" if p["shapeCache"] == "0" else "on"
    print(f"{p['query']:<14}{cache:<13}{m['score']:9.1f}{pc['50.0']:9.1f}{pc['99.0']:9.1f}{pc['99.9']:9.1f}")
