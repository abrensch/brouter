package btools.mapsplitter;

import java.util.Random;
import java.util.HashMap;

import org.junit.Assert;
import org.junit.Test;
import java.net.URL;
import java.io.File;

import btools.mapdecoder.TileDecoder;
import btools.mapdecoder.OsmTile;

public class MapsplitterTest
{
  @Test
  public void mapsplitterTest() throws Exception
  {
    URL mapurl = this.getClass().getResource( "/dreieich.osm.gz" );
    Assert.assertTrue( "test-osm-map dreieich.osm not found", mapurl != null );
    File mapfile = new File(mapurl.getFile());
    File workingDir = mapfile.getParentFile();
    File tmpdir = new File( workingDir, "tmp2" );
    tmpdir.mkdir();

    // run OsmSplitter
    File tiles = new File( tmpdir, "tiles" );
    tiles.mkdir();
    new OsmSplitter().process( tiles, mapfile );

    // run TileSplitter to split up to level 12
    new TileSplitter().process( tiles );

    new TileEncoder().process( new File( tiles, "0/0_0.ntl" ) );
    new TileDecoder().process( tiles, null, 12, 2147, 1389 );
  }
}
