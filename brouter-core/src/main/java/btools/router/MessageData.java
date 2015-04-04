/**
 * Information on matched way point
 *
 * @author ab
 */
package btools.router;



final class MessageData implements Cloneable
{
  int linkdist = 0;
  int linkelevationcost = 0;
  int linkturncost = 0;
  int linknodecost = 0;
  int linkinitcost = 0;
  
  float costfactor;
  String wayKeyValues;
  String nodeKeyValues;

  int lon;
  int lat;
  short ele;
  
  String toMessage()
  {
    if ( wayKeyValues == null )
    {
      return null;
    }
  
    int iCost = (int)(costfactor*1000 + 0.5f);
    return (lon-180000000) + "\t"
         + (lat-90000000) + "\t"
         + ele/4 + "\t"
         + linkdist + "\t"
         + iCost + "\t"
         + linkelevationcost
         + "\t" + linkturncost
         + "\t" + linknodecost
         + "\t" + linkinitcost
         + "\t" + wayKeyValues
         + "\t" + ( nodeKeyValues == null ? "" : nodeKeyValues );
  }

  void add( MessageData d )
  {
    linkdist += d.linkdist;
    linkelevationcost += d.linkelevationcost;
    linkturncost += d.linkturncost;
    linknodecost += d.linknodecost;
    linkinitcost+= d.linkinitcost;
  }

  MessageData copy()
  {
    try
    {
      return (MessageData)clone();
    }
    catch( CloneNotSupportedException e )
    {
      throw new RuntimeException( e );
    }
  }
}
