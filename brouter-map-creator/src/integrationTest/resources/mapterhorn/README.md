# swissALTI3D reference data (SwissAlti3dValidationTest)

Ground-truth reference for `SwissAlti3dValidationTest`: the converter is run over the
shared real Mapterhorn WebP tiles (in `src/testFixtures/resources/mapterhorn/`) and
compared against the Swiss national LiDAR reference along two professionally measured
Swiss Cycling Talent-ID test climbs.

## Files

- `swissalti3d_<site>.csv` — `lon,lat,height`: 89 points at 20 m spacing along each
  climb road (OSM geometry, starting at the official Swiss Cycling start markers), with
  heights from the swisstopo height service (swissALTI3D, ±0.5 m accuracy), queried
  2026-07-10. © swisstopo, Swiss open government data.

The WebP terrain tiles this suite converts (Beggingen 2145/1427+1428, Wilderswil
2137/1446) are shared fixtures — see
`brouter-map-creator/src/testFixtures/resources/mapterhorn/README.md`.

## Regenerating

Reference heights come from
`https://api3.geo.admin.ch/rest/services/height?easting=E&northing=N` (LV95) at the CSV
coordinates. The climbs are the roads through the official start points published on
https://www.swiss-cycling.ch/de/infocenter/ausdauer-teststrecken/ (Beggingen
47.7574449 N 8.5482389 E, Wilderswil 46.6575602 N 7.8536613 E), followed uphill.
