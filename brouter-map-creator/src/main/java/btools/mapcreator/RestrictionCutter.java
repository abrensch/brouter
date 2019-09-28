package btools.mapcreator;

import java.io.File;

/**
 * RestrictionCutter writes Restrictions to tiles
 *
 * - cut the way file into 45*30 - pieces
 * - enrich ways with relation information
 *
 * @author ab
 */
public class RestrictionCutter extends MapCreatorBase
{
  private WayCutter wayCutter;

  public void init( File outTileDir, WayCutter wayCutter ) throws Exception
  {
    outTileDir.mkdir();
    this.outTileDir = outTileDir;
    this.wayCutter = wayCutter;
  }

  public void finish() throws Exception
  {
    closeTileOutStreams();
  }

  public void nextRestriction( RestrictionData data ) throws Exception
  {
    int tileIndex = wayCutter.getTileIndexForNid( data.viaNid );
    if ( tileIndex != -1 )
    {
      data.writeTo( getOutStreamForTile( tileIndex ) );
    }
  }

  protected String getNameForTile( int tileIndex )
  {
    String name = wayCutter.getNameForTile( tileIndex );
    return name.substring( 0, name.length()-3 ) + "rtl";
  }

}
