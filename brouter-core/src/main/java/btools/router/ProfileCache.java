/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import java.io.File;

import btools.expressions.BExpressionContextGlobal;
import btools.expressions.BExpressionContextNode;
import btools.expressions.BExpressionContextWay;
import btools.expressions.BExpressionMetaData;

public final class ProfileCache 
{
  private static BExpressionContextWay expctxWay;
  private static BExpressionContextNode expctxNode;
 
  private static File lastLookupFile;
  private static File lastProfileFile;

  private static long lastLookupTimestamp;
  private static long lastProfileTimestamp;
  
  private static boolean profilesBusy;

  public static synchronized boolean parseProfile( RoutingContext rc )
  {
      String profileBaseDir = System.getProperty( "profileBaseDir" );
      File profileDir;
      File profileFile;
      if ( profileBaseDir == null )
      {
        profileDir = new File( rc.localFunction ).getParentFile();
        profileFile = new File( rc.localFunction ) ;
      }
      else
      {
        profileDir = new File( profileBaseDir );
        profileFile = new File( profileDir, rc.localFunction + ".brf" ) ;
      }
      rc.profileTimestamp = profileFile.lastModified();
      File lookupFile = new File( profileDir, "lookups.dat" );

      // check for re-use
      if ( expctxWay != null && expctxNode != null && !profilesBusy )
      {
        if ( profileFile.equals( lastProfileFile ) && lookupFile.equals( lastLookupFile ) )
        {
          if ( rc.profileTimestamp == lastProfileTimestamp
            && lookupFile.lastModified() ==  lastLookupTimestamp )
          {
            rc.expctxWay = expctxWay;
            rc.expctxNode = expctxNode;
            profilesBusy = true;
            rc.readGlobalConfig(expctxWay);
            return true;
          }
        }
      }
      
      BExpressionMetaData meta = new BExpressionMetaData();
      
      BExpressionContextGlobal expctxGlobal = new BExpressionContextGlobal( meta );
      rc.expctxWay = new BExpressionContextWay( rc.memoryclass * 512, meta );
      rc.expctxNode = new BExpressionContextNode( rc.memoryclass * 128, meta );
      
      meta.readMetaData( new File( profileDir, "lookups.dat" ) );

      expctxGlobal.parseFile( profileFile, null );
      expctxGlobal.evaluate( new int[0] );
      rc.readGlobalConfig(expctxGlobal);

      rc.expctxWay.parseFile( profileFile, "global" );
      rc.expctxNode.parseFile( profileFile, "global" );
      
      if ( rc.processUnusedTags )
      {
        rc.expctxWay.setAllTagsUsed();
      }

      lastProfileTimestamp = profileFile.lastModified();
      lastLookupTimestamp = lookupFile.lastModified();
      lastProfileFile = profileFile;
      lastLookupFile = lookupFile;
      expctxWay = rc.expctxWay;
      expctxNode = rc.expctxNode;
      profilesBusy = true;
      return false;
  }

  public static synchronized void releaseProfile( RoutingContext rc )
  {
    // only the thread that holds the cached instance can release it
    if ( rc.expctxWay == expctxWay && rc.expctxNode == expctxNode )
    {
      profilesBusy = false;
    }
    rc.expctxWay = null;
    rc.expctxNode = null;
  }

}
