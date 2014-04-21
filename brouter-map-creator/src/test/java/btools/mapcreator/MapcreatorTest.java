package btools.mapcreator;

import java.util.Random;
import java.util.HashMap;

import org.junit.Assert;
import org.junit.Test;
import java.net.URL;
import java.io.File;

public class MapcreatorTest
{
  @Test
  public void mapcreatorTest() throws Exception
  {
    URL mapurl = this.getClass().getResource( "/dreieich.osm.gz" );
    Assert.assertTrue( "test-osm-map dreieich.osm not found", mapurl != null );
    File mapfile = new File(mapurl.getFile());
    File workingDir = mapfile.getParentFile();
    File tmpdir = new File( workingDir, "tmp" );
    tmpdir.mkdir();

    // run OsmCutter
    File nodetiles = new File( tmpdir, "nodetiles" );
    nodetiles.mkdir();
    File lookupFile = new File( workingDir, "lookups.dat" );
    File wayFile = new File( tmpdir, "ways.dat" );
    File relFile = new File( tmpdir, "cycleways.dat" );
    new OsmCutter().process( lookupFile, nodetiles, wayFile, relFile, mapfile );

    // run NodeFilter
    File ftiles = new File( tmpdir, "ftiles" );
    ftiles.mkdir();
    new NodeFilter().process( nodetiles, wayFile, ftiles );

    // run WayCutter
    File profileReport = new File( workingDir, "trekking.brf" );
    File profileCheck = new File( workingDir, "softaccess.brf" );
    File waytiles = new File( tmpdir, "waytiles" );
    waytiles.mkdir();
    new WayCutter().process( ftiles, wayFile, waytiles, relFile, lookupFile, profileReport, profileCheck );

    // run WayCutter5
    File waytiles55 = new File( tmpdir, "waytiles55" );
    File bordernids = new File( tmpdir, "bordernids.dat" );
    waytiles55.mkdir();
    new WayCutter5().process( ftiles, waytiles, waytiles55, bordernids );

    // run NodeCutter
    File nodes55 = new File( tmpdir, "nodes55" );
    nodes55.mkdir();
    new NodeCutter().process( ftiles, nodes55 );

    // run PosUnifier
    File unodes55 = new File( tmpdir, "unodes55" );
    File bordernodes = new File( tmpdir, "bordernodes.dat" );
    unodes55.mkdir();
    new PosUnifier().process( nodes55, unodes55, bordernids, bordernodes, "/private-backup/srtm" );

    // run WayLinker
    File segments = new File( tmpdir, "segments" );
    segments.mkdir();
    File profileAllFile = new File( workingDir, "all.brf" );
    new WayLinker().process( unodes55, waytiles55, bordernodes, lookupFile, profileAllFile, segments, "rd5" );

    // run WayLinker, car subset
    File carsubset = new File( segments, "carsubset" );
    carsubset.mkdir();
    File profileCarFile = new File( workingDir, "car-test.brf" );
    new WayLinker().process( unodes55, waytiles55, bordernodes, lookupFile, profileCarFile, carsubset, "cd5" );
  }
}
