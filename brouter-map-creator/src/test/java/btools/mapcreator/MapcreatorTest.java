package btools.mapcreator;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.net.URL;

public class MapcreatorTest {
  @Test
  public void mapcreatorTest() throws Exception {
    System.setProperty("avoidMapPolling", "true");

    URL mapurl = this.getClass().getResource("/dreieich.pbf");
    Assert.assertNotNull("test-osm-map dreieich.pbf not found", mapurl);
    File mapFile = new File(mapurl.getFile());
    File workingDir = mapFile.getParentFile();
    File profileDir = new File(workingDir, "/../../../../misc/profiles2");
    File tmpdir = new File(workingDir, "tmp");
    tmpdir.mkdir();

    File nodes = new File(tmpdir, "nodetiles");
    nodes.mkdir();
    File ways = new File(tmpdir, "waytiles");
    ways.mkdir();
    File nodes55 = new File(tmpdir, "nodes55");
    nodes55.mkdir();
    File ways55 = new File(tmpdir, "waytiles55");
    ways55.mkdir();
    File lookupFile = new File(profileDir, "lookups.dat");
    File relFile = new File(tmpdir, "cycleways.dat");
    File resFile = new File(tmpdir, "restrictions.dat");
    File profileAll = new File(profileDir, "all.brf");
    File profileReport = new File(profileDir, "trekking.brf");
    File profileCheck = new File(profileDir, "softaccess.brf");
    File borderFile = new File(tmpdir, "bordernids.dat");

    OsmFastCutter.doCut(lookupFile, nodes, ways, nodes55, ways55, borderFile, relFile, resFile, profileAll, profileReport, profileCheck, mapFile, null);


    // run PosUnifier
    File unodes55 = new File(tmpdir, "unodes55");
    File bordernodes = new File(tmpdir, "bordernodes.dat");
    unodes55.mkdir();
    new PosUnifier().process(nodes55, unodes55, borderFile, bordernodes, workingDir.getAbsolutePath(), null);

    // run WayLinker
    File segments = new File(tmpdir, "segments");
    segments.mkdir();
    new WayLinker().process(unodes55, ways55, bordernodes, resFile, lookupFile, profileAll, segments, "rd5");
  }
}
