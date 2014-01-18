/**
 * Container for routig configs
 *
 * @author ab
 */
package btools.router;

import btools.expressions.BExpressionReceiver;

final class RoutingMessageHandler implements BExpressionReceiver
{
   private int ilon;
   private int ilat;

   public void setCurrentPos( int lon, int lat)
   {
     ilon = lon;
     ilat = lat;
   }


   @Override
   public void expressionWarning( String context, String message )
   {
     System.out.println( "message (lon=" + (ilon-180000000) + " lat=" + (ilat-90000000)
                          + " context " + context + "): " + message );
   }
}
