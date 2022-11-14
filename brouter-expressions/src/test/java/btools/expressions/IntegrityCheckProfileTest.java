package btools.expressions;

import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class IntegrityCheckProfileTest {

  @Test
  public void integrityTestProfiles() throws IOException {
    File workingDir = new File(".").getCanonicalFile();
    File profileDir = new File(workingDir, "../misc/profiles2");
    File[] files = profileDir.listFiles();

    assertNotNull("Missing profiles", files);

    for (File f : files) {
      if (f.getName().endsWith(".brf")) {
        BExpressionMetaData meta = new BExpressionMetaData();
        BExpressionContext expctxWay = new BExpressionContextWay(meta);
        BExpressionContext expctxNode = new BExpressionContextNode(meta);
        meta.readMetaData(new File(profileDir, "lookups.dat"));
        expctxNode.setForeignContext(expctxWay);
        expctxWay.parseFile(f, "global");
        expctxNode.parseFile(f, "global");
      }
    }
  }

}
