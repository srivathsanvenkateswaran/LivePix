#!/usr/bin/env python3
"""Builds the offline map and place-name assets.

Input directory must hold, unmodified from their publishers:
  ne_50m_land.geojson   Natural Earth 1:50m land (public domain)
  cities15000.zip       GeoNames (CC BY 4.0)
  countryInfo.txt, admin1.txt (admin1CodesASCII.txt)   GeoNames

Usage: build_geo_assets.py <input-dir> <output-dir>
"""
import json, struct, sys, zipfile
from pathlib import Path

src, out = Path(sys.argv[1]), Path(sys.argv[2])

# land.bin: big-endian int32 polygon count, then for each ring an int32 point count and
# int16 lon/lat pairs scaled to the full int16 range.
rings = []
for feature in json.loads((src / "ne_50m_land.geojson").read_text())["features"]:
    geometry = feature["geometry"]
    polygons = geometry["coordinates"] if geometry["type"] == "MultiPolygon" else [geometry["coordinates"]]
    rings += [polygon[0] for polygon in polygons]
with open(out / "land.bin", "wb") as f:
    f.write(struct.pack(">i", len(rings)))
    for ring in rings:
        f.write(struct.pack(">i", len(ring)))
        for lon, lat in ring:
            f.write(struct.pack(">hh", round(lon / 180 * 32767), round(lat / 90 * 32767)))

countries = {}
for line in (src / "countryInfo.txt").read_text().splitlines():
    if not line.startswith("#"):
        p = line.split("\t")
        countries[p[0]] = p[4]
admin1 = {p[0]: p[1] for p in (l.split("\t") for l in (src / "admin1.txt").read_text().splitlines())}

# cities.txt: "R|region|country" table lines, then "name|lat|lon|population|regionIndex".
regions, cities = {}, []
with zipfile.ZipFile(src / "cities15000.zip") as z:
    for line in z.read("cities15000.txt").decode("utf-8").splitlines():
        p = line.split("\t")
        name, lat, lon, cc, adm, pop = p[1], float(p[4]), float(p[5]), p[8], p[10], int(p[14])
        region = (admin1.get(f"{cc}.{adm}", ""), countries.get(cc, cc))
        cities.append((name, lat, lon, pop, regions.setdefault(region, len(regions))))
cities.sort(key=lambda c: -c[3])
with open(out / "cities.txt", "w", encoding="utf-8") as f:
    for (region, country) in regions:
        f.write(f"R|{region}|{country}\n")
    for name, lat, lon, pop, region in cities:
        f.write(f"{name}|{lat:.3f}|{lon:.3f}|{pop}|{region}\n")
print(f"{len(rings)} land rings, {len(cities)} cities, {len(regions)} regions")
