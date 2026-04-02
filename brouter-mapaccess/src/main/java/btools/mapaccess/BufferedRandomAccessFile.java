package btools.mapaccess;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Buffered wrapper around RandomAccessFile optimized for cloud storage.
 *
 * Cloud storage (S3, GCS, etc.) has high latency per request but good throughput.
 * This class reduces the number of read operations by using a large buffer
 * (1MB default) to amortize network round-trips.
 *
 * Performance improvements for cloud storage:
 * - Standard: Many small reads = many network round-trips = high latency
 * - Buffered: Few large reads = fewer network round-trips = lower total latency
 */
public class BufferedRandomAccessFile implements AbstractRandomAccessFile {

  private final RandomAccessFile raf;
  private final byte[] buffer;
  private final int bufferSize;

  // Buffer state
  private long bufferFilePosition = -1;  // Where in file does buffer start
  private int bufferValidBytes = 0;      // How many bytes in buffer are valid
  private long filePointer = 0;          // Current virtual file pointer
  private final long fileLength;

  public BufferedRandomAccessFile(File file, String mode) throws IOException {
    this(file, mode, BufferedFileReaderConfig.BUFFER_SIZE);
  }

  public BufferedRandomAccessFile(File file, String mode, int bufferSize) throws IOException {
    this.raf = new RandomAccessFile(file, mode);
    this.bufferSize = bufferSize;
    this.buffer = new byte[bufferSize];
    this.fileLength = raf.length();
  }

  /**
   * Seeks to a position in the file.
   */
  public void seek(long pos) throws IOException {
    if (pos < 0) {
      throw new IOException("Negative seek offset");
    }
    filePointer = pos;
  }

  /**
   * Returns the current file pointer position.
   */
  public long getFilePointer() {
    return filePointer;
  }

  /**
   * Returns the length of the file.
   */
  public long length() throws IOException {
    return fileLength;
  }

  /**
   * Reads a single byte from the file.
   */
  public int read() throws IOException {
    byte[] b = new byte[1];
    int n = read(b, 0, 1);
    return (n == 1) ? (b[0] & 0xFF) : -1;
  }

  /**
   * Reads up to len bytes from the file into the array.
   */
  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }

    int totalBytesReadThisCall = 0;

    while (len > 0) {
      // Check if we can read from buffer
      if (isInBuffer(filePointer)) {
        // Buffer hit!
        int offsetInBuffer = (int) (filePointer - bufferFilePosition);
        int bytesAvailable = bufferValidBytes - offsetInBuffer;
        int bytesToCopy = Math.min(len, bytesAvailable);

        System.arraycopy(buffer, offsetInBuffer, b, off, bytesToCopy);

        filePointer += bytesToCopy;
        off += bytesToCopy;
        len -= bytesToCopy;
        totalBytesReadThisCall += bytesToCopy;

      } else {
        // Buffer miss - need to refill
        refillBuffer(filePointer);

        // If still no data available (EOF), break
        if (!isInBuffer(filePointer)) {
          break;
        }
      }
    }

    return totalBytesReadThisCall > 0 ? totalBytesReadThisCall : -1;
  }

  /**
   * Reads exactly len bytes from the file.
   * Throws IOException if not enough bytes available.
   */
  public void readFully(byte[] b, int off, int len) throws IOException {
    int total = 0;
    while (total < len) {
      int bytesRead = read(b, off + total, len - total);
      if (bytesRead < 0) {
        throw new IOException("EOF reached before reading requested bytes");
      }
      total += bytesRead;
    }
  }

  /**
   * Reads exactly b.length bytes from the file.
   */
  public void readFully(byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  /**
   * Checks if a given file position is currently in the buffer.
   */
  private boolean isInBuffer(long pos) {
    return bufferFilePosition != -1
        && pos >= bufferFilePosition
        && pos < bufferFilePosition + bufferValidBytes;
  }

  /**
   * Refills the buffer starting at the given file position.
   * Uses read-ahead strategy: reads full buffer even if only small amount needed.
   */
  private void refillBuffer(long pos) throws IOException {
    // Position to read from
    bufferFilePosition = pos;

    // Calculate how much to read (don't read past end of file)
    long remainingInFile = fileLength - pos;
    int bytesToRead = (int) Math.min(bufferSize, remainingInFile);

    if (bytesToRead <= 0) {
      bufferValidBytes = 0;
      return;
    }

    // Read from underlying file
    raf.seek(pos);
    bufferValidBytes = 0;

    // Read in chunks if needed
    while (bufferValidBytes < bytesToRead) {
      int n = raf.read(buffer, bufferValidBytes, bytesToRead - bufferValidBytes);
      if (n < 0) {
        break; // EOF
      }
      bufferValidBytes += n;
    }
  }

  /**
   * Closes the underlying file.
   */
  @Override
  public void close() throws IOException {
    raf.close();
  }
}