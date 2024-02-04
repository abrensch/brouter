package btools.expressions;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.net.URL;

public class EncodeDecodeTest {
  @Test
  public void encodeDecodeTest() {
    URL testpurl = this.getClass().getResource("/dummy.txt");
    File workingDir = new File(testpurl.getFile()).getParentFile();
    File profileDir = new File(workingDir, "/../../../../misc/profiles2");
    //File lookupFile = new File( profileDir, "lookups.dat" );
    // add a test lookup
    URL testlookup = this.getClass().getResource("/lookups_test.dat");
    File lookupFile = new File(testlookup.getPath());

    // read lookup.dat + trekking.brf
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay expctxWay = new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);
    expctxWay.parseFile(new File(profileDir, "trekking.brf"), "global");

    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "depth=1'6\"",
//    "depth=6 feet",
      "maxheight=5.1m",
      "maxdraft=~3 m - 4 m",
      "reversedirection=yes"
    };

    // encode the tags into 64 bit description word
    int[] lookupData = expctxWay.createNewLookupData();
    for (String arg : tags) {
      int idx = arg.indexOf('=');
      if (idx < 0)
        throw new IllegalArgumentException("bad argument (should be <tag>=<value>): " + arg);
      String key = arg.substring(0, idx);
      String value = arg.substring(idx + 1);

      expctxWay.addLookupValue(key, value, lookupData);
    }
    byte[] description = expctxWay.encode(lookupData);

    // calculate the cost factor from that description
    expctxWay.evaluate(true, description); // true = "reversedirection=yes"  (not encoded in description anymore)

    System.out.println("description: " + expctxWay.getKeyValueDescription(true, description));

    float costfactor = expctxWay.getCostfactor();
    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 5.15) < 0.00001);
  }
}
