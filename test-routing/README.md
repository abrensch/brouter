# Test Routing Files

This directory contains test routes and scripts for validating BRouter's enhanced routing features.

## Files

### GPX Route Files

- **`car-test-route.gpx`**: A car route from Oslo area (59.589069, 10.2079291) to Trondheim area (61.9978187, 10.8879825) via an intermediate waypoint. This route demonstrates:
  - Car-specific break periods (every 4.5 hours of driving, 45-minute break)
  - Time-based rest stop suggestions
  - Car routing profile (`car-vario`)

- **`cycling-test-route.gpx`**: A cycling route from Oslo (59.9063856, 10.7696719) to Trondheim (63.4268907, 10.3955186) via two intermediate waypoints. This route demonstrates:
  - Cycling rest stops (Main Rest every 28.24 km)
  - Distance-based rest suggestions for trekking cyclists
  - Cycling routing profile (`fastbike`)
  - POI search for water points and cabins/huts near rest stops

- **`trekking-test-route.gpx`**: A trekking route from 61.8937872, 9.4865861 to 61.8842863, 10.0113469 via 61.8788128, 9.7959691. This route demonstrates:
  - Hiking rest stops (every 11.295 km, old Norwegian mile)
  - Distance-based rest suggestions for trekking cyclists
  - Trekking routing profile (`trekking`)
  - POI search for water points and cabins/huts near rest stops
  - Country-specific camping rules

- **`testtrack1.gpx`** and **`testtrack2.gpx`**: Additional test routes for validation.

### Generation Scripts

- **`generate_car_route.sh`**: Script to regenerate the car test route. Requires:
  - Map segments: `E10_N55.rd5`, `E10_N60.rd5` in `test_segments/` directory
  - BRouter JAR file: `brouter-server/build/libs/brouter-*-all.jar`
  
  Usage:
  ```bash
  bash test-routing/generate_car_route.sh
  ```

- **`generate_cycling_route.sh`**: Script to regenerate the cycling test route. Requires:
  - Map segments: `E10_N55.rd5`, `E10_N60.rd5` in `test_segments/` directory
  - BRouter JAR file: `brouter-server/build/libs/brouter-*-all.jar`
  
  Usage:
  ```bash
  bash test-routing/generate_cycling_route.sh
  ```

- **`generate_trekking_route.sh`**: Script to regenerate the trekking test route. Requires:
  - Map segments: `E10_N55.rd5`, `E10_N60.rd5` in `test_segments/` directory
  - BRouter JAR file: `brouter-server/build/libs/brouter-*-all.jar`
  
  Usage:
  ```bash
  bash test-routing/generate_trekking_route.sh
  ```

## Features Demonstrated

### Rest Stop Waypoints

All routes include waypoints marking suggested rest stops:
- **Car routes**: Time-based breaks (e.g., "Break 1 (4.5h driving, 45min break)")
- **Cycling routes**: Distance-based rest stops (e.g., "Main Rest 1 (28.24 km)")
- **Hiking routes**: Distance-based rest stops based on old Norwegian miles (11.295 km main, 2.275 km alternative)

### POI Search (Water Points and Cabins)

For hiking and cycling routes, BRouter automatically searches for nearby Points of Interest (POIs) around rest stops:
- **Water points**: Drinking water, fountains, and springs within 2 km
- **Cabins/Huts**: Alpine huts, wilderness huts, and cabins within 5 km
  - Only includes accessible cabins: `access=yes` (or no access tag) and `locked=no` (or no locked tag)
  - Includes network cabins (e.g., Den Norske Turistforening) but excludes those requiring membership (e.g., `dnt:lock=yes`)
  - Network name displayed in waypoint names when available

POI information is included in the waypoint names when available:
```
Main Rest 1 (28.24 km) | Water: 450m | Cabin: 1200m
Main Rest 2 (56.48 km) | Cabin: 800m (Den Norske Turistforening)
```

If a water point is a natural spring, a warning is included:
```
Rest Stop 2 (11.3 km) | Water: 800m (spring)
```

## Route Details

### Car Route
- **Start**: 59.589069, 10.2079291 (Oslo area)
- **Via**: 61.6468135, 10.462849
- **End**: 61.9978187, 10.8879825 (Trondheim area)
- **Profile**: `car-vario`
- **Distance**: ~347 km
- **Breaks**: Suggested every 4.5 hours of driving

### Cycling Route
- **Start**: 59.9063856, 10.7696719 (Oslo)
- **Via**: 60.7927994, 11.0431314, 61.5484373, 9.966244
- **End**: 63.4268907, 10.3955186 (Trondheim)
- **Profile**: `fastbike`
- **Distance**: ~630 km
- **Rest Stops**: Main rest every 28.24 km (scaled from hiking distances)

### Trekking Route
- **Start**: 61.8937872, 9.4865861
- **Via**: 61.8788128, 9.7959691
- **End**: 61.8842863, 10.0113469
- **Profile**: `trekking`
- **Distance**: ~56.9 km
- **Rest Stops**: Every 11.295 km (old Norwegian mile)

## Regenerating Routes

To regenerate routes with the latest BRouter code:

1. Build the BRouter JAR:
   ```bash
   ./gradlew :brouter-server:fatJar
   ```

2. Ensure map segments are available in `test_segments/`:
   - Download from [brouter.de/segments4/](https://brouter.de/brouter/segments4/)
   - Or generate using the regeneration script (see main README)

3. Run the generation scripts:
   ```bash
   bash test-routing/generate_car_route.sh
   bash test-routing/generate_cycling_route.sh
   bash test-routing/generate_trekking_route.sh
   ```

## Viewing Routes

These GPX files can be opened in:
- [GPX Viewer](https://www.gpxviewer.com/)
- [QGIS](https://qgis.org/)
- [JOSM](https://josm.openstreetmap.de/)
- Any GPS navigation app that supports GPX import
- Online tools like [GPS Visualizer](https://www.gpsvisualizer.com/)

## Notes

- POI search requires map segments that include node descriptions (tags)
- POI information may not be available for all rest stops if no POIs are found nearby
- The search radius is 2 km for water points and 5 km for cabins/huts
- Routes are generated for Norway and require Norwegian map segments
- **Important**: For POI search to work, segments must be regenerated with POI nodes included
  - Use `regenerate_segments_with_poi.sh` to regenerate segments with POI data
  - The NodeFilter has been modified to include POI nodes (nodes with tags)
  - Existing segments (generated before this change) won't have POI nodes
  - If waypoint names don't show "| Water:" or "| Cabin:", the segments need regeneration

