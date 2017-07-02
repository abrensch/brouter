/**
 * This program
 * - reads an *.osm from stdin
 * - writes zoom 0 tiles
 *
 * @author ab
 */
package btools.mapsplitter;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;

import btools.util.DiffCoderDataOutputStream;

public class OsmSplitter extends MapCreatorBase
{
  private long recordCnt;
  private long nodesParsed;
  private long waysParsed;
  private long relsParsed;
  private long changesetsParsed;

  private DataOutputStream wayDos;
  private DataOutputStream relDos;
  private DiffCoderDataOutputStream nodeDos;

  public static void main(String[] args) throws Exception
  {
    System.out.println("*** OsmSplitter : transform an osm map to zoom 0 tiles");
    if (args.length != 2)
    {
      System.out.println("usage : java OsmSplitter <tile-dir> <inputfile> ");
      return;
    }

    new OsmSplitter().process(
                   new File( args[0] )
                 , new File( args[1] )
                		 );
  }

  public void process (File outTileDir, File mapFile ) throws Exception
  {
    if ( !outTileDir.isDirectory() ) throw new RuntimeException( "out tile directory " + outTileDir + " does not exist" );

    File z0 = new File( outTileDir, "0" );
    z0.mkdirs();
    File ways = new File( z0, "0_0.wtl" );
    File nodes = new File( z0, "0_0.ntl" );
    File rels = new File( z0, "0_0.rtl" );

    wayDos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( ways ) ) );
    relDos = new DataOutputStream( new BufferedOutputStream( new FileOutputStream( rels ) ) );
    nodeDos = new DiffCoderDataOutputStream( new BufferedOutputStream( new FileOutputStream( nodes ) ) );

    // read the osm map into memory
    long t0 = System.currentTimeMillis();
    new OsmParser2().readMap( mapFile, this, this, this );
    long t1 = System.currentTimeMillis();
    
    System.out.println( "parsing time (ms) =" + (t1-t0) );

    // close all files
    wayDos.close();
    nodeDos.close();

    System.out.println( statsLine() );
  }

  private void checkStats()
  {
    if ( (++recordCnt % 100000) == 0 ) System.out.println( statsLine() );
  }

  private String statsLine()
  {
    return "records read: " + recordCnt + " nodes=" + nodesParsed + " ways=" + waysParsed + " rels=" + relsParsed + " changesets=" + changesetsParsed;
  }


  @Override
  public void nextNode( NodeData n ) throws Exception
  {
    nodesParsed++;
    checkStats();
    n.writeTo( nodeDos );
  }


  @Override
  public void nextWay( WayData w ) throws Exception
  {
    waysParsed++;
    checkStats();
    
    w.writeTo( wayDos );
  }

  @Override
  public void nextRelation( RelationData r ) throws Exception
  {
    relsParsed++;
    checkStats();
    
    r.writeTo( relDos );
  }
}
