package btools.util;


public interface ProgressListener
{
  public void updateProgress(String task, int progress);
  
  public boolean isCanceled();
}
