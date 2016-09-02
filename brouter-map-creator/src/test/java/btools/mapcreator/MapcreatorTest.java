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
    File profileDir = new File( workingDir, "/../../../misc/profiles2" );
    File tmpdir = new File( workingDir, "tmp" );
    tmpdir.mkdir();

    // run OsmCutter
    File nodetiles = new File( tmpdir, "nodetiles" );
    nodetiles.mkdir();
    File lookupFile = new File( profileDir, "lookups.dat" );
    File wayFile = new File( tmpdir, "ways.dat" );
    File relFile = new File( tmpdir, "cycleways.dat" );
    File profileAllFile = new File( profileDir, "all.brf" );
    new OsmCutter().process( lookupFile, nodetiles, wayFile, relFile, profileAllFile, mapfile );

    // run NodeFilter
    File ftiles = new File( tmpdir, "ftiles" );
    ftiles.mkdir();
    new NodeFilter().process( nodetiles, wayFile, ftiles );

    // run RelationMerger
    File wayFile2 = new File( tmpdir, "ways2.dat" );
    File profileReport = new File( profileDir, "trekking.brf" );
    File profileCheck = new File( profileDir, "softaccess.brf" );
    new RelationMerger().process( wayFile, wayFile2, relFile, lookupFile, profileReport, profileCheck );

    // run WayCutter
    File waytiles = new File( tmpdir, "waytiles" );
    waytiles.mkdir();
    new WayCutter().process( ftiles, wayFile2, waytiles );

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
    new WayLinker().process( unodes55, waytiles55, bordernodes, lookupFile, profileAllFile, segments, "rd5" );
  }
}
