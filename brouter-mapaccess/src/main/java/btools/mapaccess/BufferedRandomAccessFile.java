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
 */
public class BufferedRandomAccessFile implements AbstractRandomAccessFile {

  private final RandomAccessFile raf;
  private final byte[] buffer;
  private final int bufferSize;


  private long bufferFilePosition = -1;
  private int bufferValidBytes = 0;
  private long filePointer = 0;
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

  public void seek(long pos) throws IOException {
    if (pos < 0) {
      throw new IOException("Negative seek offset");
    }
    filePointer = pos;
  }

  public long getFilePointer() {
    return filePointer;
  }

  public long length() throws IOException {
    return fileLength;
  }

  public int read() throws IOException {
    byte[] b = new byte[1];
    int n = read(b, 0, 1);
    return (n == 1) ? (b[0] & 0xFF) : -1;
  }

  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }

    int totalBytesReadThisCall = 0;

    while (len > 0) {
      if (isInBuffer(filePointer)) {
        int offsetInBuffer = (int) (filePointer - bufferFilePosition);
        int bytesAvailable = bufferValidBytes - offsetInBuffer;
        int bytesToCopy = Math.min(len, bytesAvailable);

        System.arraycopy(buffer, offsetInBuffer, b, off, bytesToCopy);

        filePointer += bytesToCopy;
        off += bytesToCopy;
        len -= bytesToCopy;
        totalBytesReadThisCall += bytesToCopy;

      } else {
        refillBuffer(filePointer);

        if (!isInBuffer(filePointer)) {
          break;
        }
      }
    }

    return totalBytesReadThisCall > 0 ? totalBytesReadThisCall : -1;
  }

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

  public void readFully(byte[] b) throws IOException {
    readFully(b, 0, b.length);
  }

  private boolean isInBuffer(long pos) {
    return bufferFilePosition != -1
        && pos >= bufferFilePosition
        && pos < bufferFilePosition + bufferValidBytes;
  }


  private void refillBuffer(long pos) throws IOException {
    bufferFilePosition = pos;

    long remainingInFile = fileLength - pos;
    int bytesToRead = (int) Math.min(bufferSize, remainingInFile);

    if (bytesToRead <= 0) {
      bufferValidBytes = 0;
      return;
    }

    raf.seek(pos);
    bufferValidBytes = 0;

    while (bufferValidBytes < bytesToRead) {
      int n = raf.read(buffer, bufferValidBytes, bytesToRead - bufferValidBytes);
      if (n < 0) {
        break; // EOF
      }
      bufferValidBytes += n;
    }
  }

  @Override
  public void close() throws IOException {
    raf.close();
  }
}
