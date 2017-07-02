package btools.mapdecoder;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.Inflater;

/**
 * Manage the mapping between locale and native node indexes
 */
public class LocaleIndexMapping
{
  private int[] refZoomDelta;
  private int[] refNativeIndex;
  private OsmTile[] tileForZoomDelta;

  public LocaleIndexMapping( OsmTile tile, BitReadBuffer brb ) throws Exception
  {
    // prepare the locale index array
    int localeNodeCount = brb.decodeInt();
    refZoomDelta = new int[localeNodeCount];
    refNativeIndex = new int[localeNodeCount];

    tileForZoomDelta = new OsmTile[tile.zoom + 1];
    for( OsmTile t = tile; t != null; t = t.parent )
    {
      tileForZoomDelta[tile.zoom-t.zoom] = t;
    }

    // decode the down-zoom refs
    for( int zoomDelta=tile.zoom; zoomDelta > 0; zoomDelta-- )
    {
      long[] localeIndexes = brb.decodeSortedArray();
      long[] nativeIndexes = brb.decodeSortedArray();
      
      for( int i=0; i<localeIndexes.length; i++ )
      {
        int idx = (int)localeIndexes[i];
        refZoomDelta[idx] = zoomDelta;
        refNativeIndex[idx] = (int)nativeIndexes[i];
      }        
    }

    // prepare locale->native mapping for zoomDelta=0
    int localeIdx = 0;
    int nodecount = tile.nodePositions.length;
    for( int i=0; i<nodecount; i++)
    {
      while( refZoomDelta[localeIdx] != 0 )
      {
        localeIdx++;
      }
      refNativeIndex[localeIdx++] = i;
    }
  }
  
  public OsmNode nodeForLocaleIndex( int localeIndex )
  {
    int zoomDelta = refZoomDelta[localeIndex];
    int nativeIndex = refNativeIndex[localeIndex];
    return tileForZoomDelta[zoomDelta].nodes.get( nativeIndex );
  }

  public OsmWay getWay( int zoomDelta, int nativeIndex )
  {
    return tileForZoomDelta[zoomDelta].ways.get( nativeIndex );
  }
}
