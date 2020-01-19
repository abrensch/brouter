package btools.util;

public interface ProgressListener
{
  public void updateProgress( String progress );
  
  public boolean isCanceled();
}
