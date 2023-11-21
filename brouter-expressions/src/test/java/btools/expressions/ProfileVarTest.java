package btools.expressions;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.net.URL;

public class ProfileVarTest {
  @Test
  public void varTest() {
    URL testpurl = this.getClass().getResource("/dummy.txt");
    File workingDir = new File(testpurl.getFile()).getParentFile();
    File profileDir = new File(workingDir, "/../../../../misc/profiles2");
    // add a test lookup to check other lookups
    // File lookupFile = new File(workingDir, "lookups_test.dat");
    File lookupFile = new File(profileDir, "lookups.dat");

    // read lookup.dat + trekking.brf
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContextWay expctxWay = new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);
    // use a test profile
    // expctxWay.parseFile(new File(profileDir, "trekking.brf"), "global");
    expctxWay.parseFile(new File(workingDir, "profile_minimal.brf"), "global");

    String[] way_tags = {
      "highway=track",
      "tracktype=grade4",
      "bicycle=yes",
      "surface=compacted"
    };

    // encode the tags into 64 bit description word
    int[] lookupData = expctxWay.createNewLookupData();
    for (String arg : way_tags) {
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
    float turncost = expctxWay.getTurncost();
    System.out.println("vars: " + expctxWay.getVariableKeyValue());

    Assert.assertTrue("turncost mismatch", Math.abs(turncost - 0) < 0.00001);
  }
}
