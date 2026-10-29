"""
Line coverage per module and per package from the JaCoCo XML reports.

    ./gradlew test integrationTest jacocoTestReport
    python bench/coverage.py            # table
    python bench/coverage.py --badge    # also writes docs/coverage.svg

"Core" is what the build guide asks 80%+ for: SQL analysis, rules, masking, and the wire protocol.
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORTS = {
    "common": Path("common/build/reports/jacoco/test/jacocoTestReport.xml"),
    "proxy": Path("proxy/build/reports/jacoco/test/jacocoTestReport.xml"),
    "admin-api": Path("admin-api/build/reports/jacoco/test/jacocoTestReport.xml"),
}
# The proxy's decision path; admin-api's "rules" package is a REST controller, not core.
CORE = {"common.analysis", "common.rules", "common.masking", "proxy.masking", "proxy.protocol", "proxy.rules"}


def lines(el):
    for c in el.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("covered")), int(c.get("missed"))
    return 0, 0


def pct(cov, miss):
    return 100.0 * cov / max(cov + miss, 1)


core_cov = core_miss = all_cov = all_miss = 0
print(f"{'package':<42}{'lines':>8}{'covered':>10}")
for module, path in REPORTS.items():
    if not path.exists():
        print(f"{module}: no report at {path}")
        continue
    root = ET.parse(path).getroot()
    for pkg in root.findall("package"):
        cov, miss = lines(pkg)
        name = pkg.get("name").replace("/", ".")
        short = name.removeprefix("dev.queryguard.")
        is_core = short in CORE
        print(f"{short + (' *' if is_core else ''):<42}{cov + miss:>8}{pct(cov, miss):>9.1f}%")
        all_cov, all_miss = all_cov + cov, all_miss + miss
        if is_core:
            core_cov, core_miss = core_cov + cov, core_miss + miss

print(f"\n{'core packages (*)':<42}{core_cov + core_miss:>8}{pct(core_cov, core_miss):>9.1f}%")
print(f"{'everything':<42}{all_cov + all_miss:>8}{pct(all_cov, all_miss):>9.1f}%")

if "--badge" in sys.argv:
    value = pct(core_cov, core_miss)
    color = "#4c1" if value >= 80 else "#dfb317" if value >= 60 else "#e05d44"
    label, text = "core coverage", f"{value:.0f}%"
    lw, vw = 92, 42
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{lw + vw}" height="20" role="img" aria-label="{label}: {text}">
<rect width="{lw}" height="20" fill="#555"/><rect x="{lw}" width="{vw}" height="20" fill="{color}"/>
<g fill="#fff" text-anchor="middle" font-family="Verdana,DejaVu Sans,sans-serif" font-size="11">
<text x="{lw / 2}" y="14">{label}</text><text x="{lw + vw / 2}" y="14">{text}</text></g></svg>
"""
    Path("docs/coverage.svg").write_text(svg, encoding="utf-8")
    print("wrote docs/coverage.svg")
