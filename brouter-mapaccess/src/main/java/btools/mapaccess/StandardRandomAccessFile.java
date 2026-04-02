package btools.mapaccess;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Standard implementation using RandomAccessFile.
 */
public class StandardRandomAccessFile implements AbstractRandomAccessFile {

  private final RandomAccessFile raf;

  public StandardRandomAccessFile(File file, String mode) throws IOException {
    this.raf = new RandomAccessFile(file, mode);
  }

  @Override
  public void seek(long pos) throws IOException {
    raf.seek(pos);
  }

  @Override
  public void readFully(byte[] b, int off, int len) throws IOException {
    raf.readFully(b, off, len);
  }

  @Override
  public long length() throws IOException {
    return raf.length();
  }

  @Override
  public void close() throws IOException {
    raf.close();
  }

  /**
   * Get the underlying RandomAccessFile for compatibility.
   */
  public RandomAccessFile getUnderlying() {
    return raf;
  }
}