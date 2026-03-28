package btools.expressions;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;

public class EncodeDecodeTest {

  static BExpressionContextWay expctxWay;

  //@Test
  @Before
  public void prepareEncodeDecodeTest() {
    if (expctxWay == null) {
      URL testpurl = this.getClass().getResource("/dummy.txt");
      File workingDir = new File(testpurl.getFile()).getParentFile();
      File profileDir = new File(workingDir, "/../../../../misc/profiles2");
      //File lookupFile = new File( profileDir, "lookups.dat" );
      // add a test lookup
      URL testlookup = this.getClass().getResource("/lookups_test.dat");
      File lookupFile = new File(testlookup.getPath());

      URL testprofile = this.getClass().getResource("/soft_test.brf");
      File profileFile = new File(testprofile.getPath());

      // read lookup.dat + trekking.brf
      BExpressionMetaData meta = new BExpressionMetaData();
      expctxWay = new BExpressionContextWay(meta);
      meta.readMetaData(lookupFile);
      expctxWay.parseFile(profileFile, "global");

      Assert.assertNotNull("no way data", expctxWay);
    }
  }

  @Test
  public void encodeDecodeTestSpeed() {
    Assert.assertNotNull("no way data", expctxWay);
    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "maxspeed=30"
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

    float costfactor = expctxWay.getCostfactor();

    System.out.println("test speed " + costfactor + "\ndescription: " + expctxWay.getKeyValueDescription(true, description));

    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 30.0f) < 0.00001f);
  }

  @Test
  public void encodeDecodeTestSpeedUnknown() {
    Assert.assertNotNull("no way data", expctxWay);
    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "maxspeed=32"
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

    float costfactor = expctxWay.getCostfactor();

    System.out.println("test unknown speed " + costfactor + "\ndescription: " + expctxWay.getKeyValueDescription(true, description));

    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 0.0f) < 0.00001f);
  }

  @Test
  public void encodeDecodeTestHeight() {
    Assert.assertNotNull("no way data", expctxWay);
    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "maxheight=default"
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

    float costfactor = expctxWay.getCostfactor();

    System.out.println("test height " + costfactor + "\ndescription: " + expctxWay.getKeyValueDescription(true, description));

    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 1.0f) < 0.00001f);
  }

  @Test
  public void encodeDecodeTestHeightUnknown() {
    Assert.assertNotNull("no way data", expctxWay);
    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "maxheight=below_default"
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

    float costfactor = expctxWay.getCostfactor();

    System.out.println("test unknown height " + costfactor + "\ndescription: " + expctxWay.getKeyValueDescription(true, description));

    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 1.0f) < 0.00001f);
  }


  @Test
  public void encodeDecodeTestValues() {
    Assert.assertNotNull("no way data", expctxWay);
    String[] tags = {
      "highway=residential",
      "oneway=yes",
      "depth=1'6\"",
//    "depth=6 feet",
      "maxheight=5.1m",
      "maxweight=5000 lbs",
      "maxdraft=~3 m - 4 m"
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

    float costfactor = expctxWay.getCostfactor();

    System.out.println("test values " + costfactor + "\ndescription: " + expctxWay.getKeyValueDescription(true, description));

    Assert.assertTrue("costfactor mismatch", Math.abs(costfactor - 1.0f) < 0.00001f);
  }

}
