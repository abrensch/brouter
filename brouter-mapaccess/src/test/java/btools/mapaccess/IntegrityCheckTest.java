package btools.mapaccess;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public class IntegrityCheckTest {
  @Test
  public void integrityTest() throws IOException {
    File workingDir = new File(".").getCanonicalFile();
    File segmentDir = new File(workingDir, "../brouter-map-creator/build/resources/test/tmp/segments");
    File[] files = segmentDir.listFiles();

    assertNotNull("Missing segments", files);

    for (File f : files) {
      assertNull(PhysicalFile.checkFileIntegrity(f));
    }
  }

}
