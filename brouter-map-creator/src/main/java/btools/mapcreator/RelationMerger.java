package btools.mapcreator;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.util.HashMap;

import btools.expressions.BExpressionContext;
import btools.expressions.BExpressionMetaData;
import btools.util.CompactLongSet;
import btools.util.FrozenLongSet;

/**
 * RelationMerger does 1 step in map processing:
 *
 * - enrich ways with relation information
 *
 * @author ab
 */
public class RelationMerger extends MapCreatorBase
{
  private HashMap<String,CompactLongSet> routesets;
  private CompactLongSet routesetall;
  private BExpressionContext expctxReport;
  private BExpressionContext expctxCheck;
 // private BExpressionContext expctxStat;

  private DataOutputStream wayOutStream;
  
  public static void main(String[] args) throws Exception
  {
    System.out.println("*** RelationMerger: merge relations into ways" );
    if (args.length != 6)
    {
      System.out.println("usage: java RelationMerger <way-file-in> <way-file-out> <relation-file> <lookup-file> <report-profile> <check-profile>" );

      return;
    }
    new RelationMerger().process( new File( args[0] ), new File( args[1] ), new File( args[2] ), new File( args[3] ), new File( args[4] ), new File( args[5] ) );
  }

  public void process( File wayFileIn, File wayFileOut, File relationFileIn, File lookupFile, File reportProfile, File checkProfile ) throws Exception
  {
    // read lookup + profile for relation access-check
	BExpressionMetaData metaReport = new BExpressionMetaData();
    expctxReport = new BExpressionContext("way", metaReport );
    metaReport.readMetaData( lookupFile );

	BExpressionMetaData metaCheck = new BExpressionMetaData();
    expctxCheck = new BExpressionContext("way", metaCheck );
    metaCheck.readMetaData( lookupFile );

    expctxReport.parseFile( reportProfile, "global" );
    expctxCheck.parseFile( checkProfile, "global" );
    // expctxStat = new BExpressionContext("way");
    
    // *** read the relation file into sets for each processed tag
    routesets = new HashMap<String,CompactLongSet>();
    routesetall = new CompactLongSet();
    DataInputStream dis = createInStream( relationFileIn );
    try
    {
      for(;;)
      {
        long rid = readId( dis );
        String route = dis.readUTF();
        String network = dis.readUTF();
        
        String tagname = "route_" + route + "_" + network;
        
        CompactLongSet routeset = null;
        if ( expctxCheck.getLookupNameIdx(tagname)  >= 0 )
        {
        	routeset = routesets.get( tagname );
        	if ( routeset == null )
        	{
        		routeset = new CompactLongSet();
        		routesets.put( tagname, routeset );
        	}
        }
        	
        for(;;)
        {
          long wid = readId( dis );
          if ( wid == -1 ) break;
    	  // expctxStat.addLookupValue( tagname, "yes", null );
          if ( routeset != null && !routeset.contains( wid ) )
          {
        	  routeset.add( wid );
        	  routesetall.add( wid );
          }
        }
      }
    }
    catch( EOFException eof )
    {
      dis.close();
    }
    for( String tagname : routesets.keySet() )
    {
    	CompactLongSet routeset = new FrozenLongSet( routesets.get( tagname ) );
    	routesets.put( tagname, routeset );
        System.out.println( "marked " + routeset.size() + " routes for tag: " + tagname );
    }

    // *** finally process the way-file
    wayOutStream = createOutStream( wayFileOut );
    new WayIterator( this, true ).processFile( wayFileIn );
    wayOutStream.close();

//    System.out.println( "-------- route-statistics -------- " );
//    expctxStat.dumpStatistics();
}

  @Override
  public void nextWay( WayData data ) throws Exception
  {
    // propagate the route-bits
    if ( routesetall.contains( data.wid ) )
    {
      boolean ok = true;
      // check access and log a warning for conflicts
      expctxReport.evaluate( false, data.description, null );
      boolean warn = expctxReport.getCostfactor() >= 10000.;
      if ( warn )
      {
          expctxCheck.evaluate( false, data.description, null );
          ok = expctxCheck.getCostfactor() < 10000.;

          System.out.println( "** relation access conflict for wid = " + data.wid + " tags:" + expctxReport.getKeyValueDescription( data.description ) + " (ok=" + ok + ")"  );
      }
    	
      if ( ok )
      {
    	expctxReport.decode( data.description );
        for( String tagname : routesets.keySet() )
        {
    	  CompactLongSet routeset = routesets.get( tagname );
    	  if ( routeset.contains( data.wid ) ) expctxReport.addLookupValue( tagname, 2 );
    	}
    	data.description = expctxReport.encode();
      }
    }

    data.writeTo( wayOutStream );
  }

}
