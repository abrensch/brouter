package btools.mapcreator;

import static org.junit.Assert.assertEquals;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ElevationRasterTileConverterTest {
  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void hgtConversionPreservesZeroAndNoData() throws Exception {
    File hgt = temporaryFolder.newFile("N52E001.hgt");
    File bef = temporaryFolder.newFile("srtm_37_02.bef");
    short[] samples = {-1, 0, 1, Short.MIN_VALUE, -32767};
    try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(hgt.toPath())))) {
      for (int row = 0; row <= 1200; row++) {
        for (int col = 0; col <= 1200; col++) {
          output.writeShort(samples[Math.min(col / 240, samples.length - 1)]);
        }
      }
    }

    new ElevationRasterTileConverter().doConvert(temporaryFolder.getRoot().getPath(), 0, 50, bef.getPath(), 1200, null);

    try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(bef.toPath()))) {
      ElevationRaster raster = new ElevationRasterCoder().decodeRaster(input);
      // Coordinates use offset microdegrees. The raster returns quarter metres.
      int latitude = 142_500_000;
      assertEquals(-4, raster.getElevation(181_100_000, latitude));
      assertEquals(0, raster.getElevation(181_300_000, latitude));
      assertEquals(4, raster.getElevation(181_500_000, latitude));
      assertEquals(Short.MIN_VALUE, raster.getElevation(181_700_000, latitude));
      assertEquals(Short.MIN_VALUE, raster.getElevation(181_900_000, latitude));
      assertEquals(Short.MIN_VALUE, raster.getElevation(182_125_000, latitude));
    }
  }
}
