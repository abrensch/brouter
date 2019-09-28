/**
 * This program
 * - reads an *.osm from stdin
 * - writes 45*30 degree node tiles + a way file + a rel file
 *
 * @author ab
 */
package btools.mapcreator;

import java.io.File;

public class OsmFastCutter extends MapCreatorBase
{
  public static void main(String[] args) throws Exception
  {
    System.out.println("*** OsmFastCutter: cut an osm map in node-tiles + way-tiles");
    if (args.length != 11 && args.length != 12)
    {
      String common = "java OsmFastCutter <lookup-file> <node-dir> <way-dir> <node55-dir> <way55-dir> <border-file> <out-rel-file> <out-res-file> <filter-profile> <report-profile> <check-profile>";
    
      System.out.println("usage: bzip2 -dc <map> | " + common );
      System.out.println("or   : " + common + " <inputfile> " );
      return;
    }

    doCut(
                   new File( args[0] )
                 , new File( args[1] )
                 , new File( args[2] )
                 , new File( args[3] )
                 , new File( args[4] )
                 , new File( args[5] )
                 , new File( args[6] )
                 , new File( args[7] )
                 , new File( args[8] )
                 , new File( args[9] )
                 , new File( args[10] )
                 , args.length > 11 ? new File( args[11] ) : null
                		 );
  }

  public static void doCut (File lookupFile, File nodeDir, File wayDir, File node55Dir, File way55Dir, File borderFile, File relFile, File resFile, File profileAll, File profileReport, File profileCheck, File mapFile ) throws Exception
  {
    // **** run OsmCutter ****
    OsmCutter cutter = new OsmCutter();

    // ... inject WayCutter
    cutter.wayCutter = new WayCutter();
    cutter.wayCutter.init( wayDir );

    // ... inject RestrictionCutter
    cutter.restrictionCutter = new RestrictionCutter();
    cutter.restrictionCutter.init( new File( nodeDir.getParentFile(), "restrictions" ), cutter.wayCutter );

    // ... inject NodeFilter
    NodeFilter nodeFilter = new NodeFilter();
    nodeFilter.init();
    cutter.nodeFilter = nodeFilter;

    cutter.process( lookupFile, nodeDir, null, relFile, null, profileAll, mapFile );
    cutter.wayCutter.finish();
    cutter.restrictionCutter.finish();
    cutter = null;

    // ***** run WayCutter5 ****
    WayCutter5 wayCut5 = new WayCutter5();

    //... inject RelationMerger
    wayCut5.relMerger = new RelationMerger();
    wayCut5.relMerger.init( relFile, lookupFile, profileReport, profileCheck );

    // ... inject RestrictionCutter5
    wayCut5.restrictionCutter5 = new RestrictionCutter5();
    wayCut5.restrictionCutter5.init( new File( nodeDir.getParentFile(), "restrictions55" ), wayCut5 );

    //... inject NodeFilter
    wayCut5.nodeFilter = nodeFilter;

    // ... inject NodeCutter
    wayCut5.nodeCutter = new NodeCutter();
    wayCut5.nodeCutter.init( node55Dir );

    wayCut5.process( nodeDir, wayDir, way55Dir, borderFile );

  }
}
