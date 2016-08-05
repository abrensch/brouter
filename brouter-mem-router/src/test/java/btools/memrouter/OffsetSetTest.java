package btools.memrouter;

import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

public class OffsetSetTest
{
  @Test
  public void offsetSetTest() throws Exception
  {
    OffsetSet fullset = OffsetSet.fullSet();
    OffsetSet s2 = fullset.emptySet().add( 4 );

    Assert.assertTrue( "added bit not found", s2.contains( 4 ) );
    Assert.assertTrue( "found false bit", !s2.contains( 3 ) );

    Assert.assertTrue( "found bit in fullset", fullset.contains( 17 ) );

    List<Integer> offsetList = new ArrayList<Integer>();
    offsetList.add( Integer.valueOf( 4 ));
    offsetList.add( Integer.valueOf( 5 ));
    offsetList.add( Integer.valueOf( 23 ));
    offsetList.add( Integer.valueOf( 50 ));

    OffsetSet s4 =  fullset.create( offsetList );
    Assert.assertTrue( "added bit 23 not found", s4.contains( 23 ) );
    Assert.assertTrue( "found false bit 17", !s4.contains( 17 ) );
    
    OffsetSet s5 = s2.filter( s4 ); // de-select 4 from s4
    Assert.assertTrue( "added bit 5 not found", s5.contains( 5 ) );
    Assert.assertTrue( "found false bit 4", !s5.contains( 4 ) );

    OffsetSet s6 = s4.filter( s2 ); // de-select 4,5,23,50 from s2 -> = null
    Assert.assertTrue( "expected empty set", s6 == null );
    
    OsmLinkP holder = new OsmLinkP();
    holder.setOffsetSet( fullset.emptySet().add( 24 ) );
    
    OffsetSet s7 = s4.filterAndClose( holder, true );
//    Assert.assertTrue( "bit 4 too much", !s7.contains( 4 ) );
//    Assert.assertTrue( "bit 5 not found", s7.contains( 5 ) );
//    Assert.assertTrue( "bit 23 not found", s7.contains( 23 ) );
//    Assert.assertTrue( "bit 50 too much", !s7.contains( 50 ) );
  }
}
