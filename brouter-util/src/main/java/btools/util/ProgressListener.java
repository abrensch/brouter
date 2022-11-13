package btools.util;


public interface ProgressListener {
  void updateProgress(String task, int progress);

  boolean isCanceled();
}
