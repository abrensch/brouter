package btools.mapcreator;

import java.io.File;

/**
 * RestrictionCutter5 does 1 step in map-processing:
 *
 * - cut the 45*30 restriction files into 5*5 pieces
 *
 * @author ab
 */
public class RestrictionCutter5 extends MapCreatorBase
{
  private WayCutter5 wayCutter5;

  public void init( File outTileDir, WayCutter5 wayCutter5 ) throws Exception
  {
    outTileDir.mkdir();
    this.outTileDir = outTileDir;
    this.wayCutter5 = wayCutter5;
  }

  public void finish() throws Exception
  {
    closeTileOutStreams();
  }


  public void nextRestriction( RestrictionData data ) throws Exception
  {
    int tileIndex = wayCutter5.getTileIndexForNid( data.viaNid );
    if ( tileIndex != -1 )
    {
      data.writeTo( getOutStreamForTile( tileIndex ) );
    }
  }

  protected String getNameForTile( int tileIndex )
  {
    String name = wayCutter5.getNameForTile( tileIndex );
    return name.substring( 0, name.length()-3 ) + "rt5";
  }
}
