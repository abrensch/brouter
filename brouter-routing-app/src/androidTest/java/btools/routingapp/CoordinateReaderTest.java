package btools.routingapp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;


public class CoordinateReaderTest {
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void readNogoRoute() throws Exception {
    File importFolder = temporaryFolder.newFolder("brouter", "import", "tracks");
    File tempFile = new File(importFolder, "nogo_test.gpx");
    try (FileWriter writer = new FileWriter(tempFile)) {
      writer.write("<?xml version=\"1.0\"?>\n" +
        "<gpx version=\"1.1\"\n" +
        "creator=\"Viking 1.9 -- http://viking.sf.net/\"\n" +
        "xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxx=\"http://www.garmin.com/xmlschemas/GpxExtensions/v3\" xmlns:wptx1=\"http://www.garmin.com/xmlschemas/WaypointExtension/v1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v2\" xmlns:gpxpx=\"http://www.garmin.com/xmlschemas/PowerExtension/v1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd http://www.garmin.com/xmlschemas/GpxExtensions/v3 http://www8.garmin.com/xmlschemas/GpxExtensionsv3.xsd http://www.garmin.com/xmlschemas/WaypointExtension/v1 http://www8.garmin.com/xmlschemas/WaypointExtensionv1.xsd http://www.garmin.com/xmlschemas/TrackPointExtension/v2 http://www.garmin.com/xmlschemas/TrackPointExtensionv2.xsd http://www.garmin.com/xmlschemas/PowerExtensionv1.xsd\">\n" +
        "  <name>Nogo Route</name>\n" +
        "  <metadata>\n" +
        "  </metadata>\n" +
        "<rte>\n" +
        "  <name>Nogo Oststadt</name>\n" +
        "  <extensions><gpxx:TrackExtension><gpxx:DisplayColor>Red</gpxx:DisplayColor></gpxx:TrackExtension></extensions>\n" +
        "  <rtept lat=\"49.009516920259095\" lon=\"8.423623305130004\">\n" +
        "  </rtept>\n" +
        "  <rtept lat=\"49.01034732709107\" lon=\"8.434330683517455\">\n" +
        "  </rtept>\n" +
        "  <rtept lat=\"49.01247254251079\" lon=\"8.445338469314574\">\n" +
        "  </rtept>\n" +
        "</rte>\n" +
        "</gpx>");
    }

    CoordinateReader coordinateReader = CoordinateReader.obtainValidReader(temporaryFolder.getRoot().getAbsolutePath(), false);
    assertThat(coordinateReader.nogopoints, hasSize(1));
    // Name should return "Nogo Oststadt", "Nogo Route" or "nogo_test.gpx"
    assertThat(coordinateReader.nogopoints.get(0).name, equalTo("nogo_test"));
    assertThat(coordinateReader.nogopoints.get(0).radius, closeTo(810.0, 5.0));
  }

  @Test
  public void readWaypoints() throws Exception {
    File importFolder = temporaryFolder.newFolder("brouter", "import");
    File tempFile = new File(importFolder, "favourites.gpx");
    try (FileWriter writer = new FileWriter(tempFile)) {
      // https://en.wikipedia.org/wiki/GPS_Exchange_Format#Sample_GPX_document
      writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx\n" +
        "  version=\"1.1\"\n" +
        "  creator=\"Runkeeper - http://www.runkeeper.com\"\n" +
        "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
        "  xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
        "  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"\n" +
        "  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n" +
        "<wpt lat=\"37.778259000\" lon=\"-122.391386000\"><ele>3.4</ele><time>2016-06-17T23:41:03Z</time><name>from</name></wpt>\n" +
        "<wpt lat=\"37.778194000\" lon=\"-122.391226000\"><ele>3.4</ele><time>2016-06-17T23:41:13Z</time><name>to</name></wpt>\n" +
        "<wpt lat=\"37.778297000\" lon=\"-122.391174000\"><ele>3.4</ele><time>2016-06-17T23:41:18Z</time><name>via1</name></wpt>\n" +
        "<wpt lat=\"37.778378000\" lon=\"-122.391117000\"><ele>3.4</ele><time>2016-06-17T23:41:23Z</time><name>nogo100 Test</name></wpt>\n" +
        "</gpx>");
    }

    CoordinateReader coordinateReader = CoordinateReader.obtainValidReader(temporaryFolder.getRoot().getAbsolutePath(), false);
    assertThat(coordinateReader, notNullValue());
    // from, to, viaX are parsed into waypoints
    assertThat(coordinateReader.waypoints, hasSize(3));
    assertThat(coordinateReader.nogopoints, hasSize(1));
    coordinateReader.readAllPoints();
    assertThat(coordinateReader.allpoints, hasSize(4));
  }
}
