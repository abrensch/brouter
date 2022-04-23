package btools.routingapp;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.work.Data;
import androidx.work.ListenableWorker.Result;
import androidx.work.testing.TestWorkerBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public class DownloadWorkerTest {
  private Context context;
  private Executor executor;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    executor = Executors.newSingleThreadExecutor();
  }

  @Test
  public void testDownloadNewFile() {
    Data inputData = new Data.Builder()
      .putStringArray(DownloadWorker.KEY_INPUT_SEGMENT_NAMES, new String[]{"E105_N50"})
      .build();

    DownloadWorker worker =
      TestWorkerBuilder.from(context, DownloadWorker.class, executor)
        .setInputData(inputData)
        .build();

    Result result = worker.doWork();
    assertThat(result, is(Result.success()));
  }

  @Test
  public void testDownloadInvalidSegment() {
    Data inputData = new Data.Builder()
      .putStringArray(DownloadWorker.KEY_INPUT_SEGMENT_NAMES, new String[]{"X00"})
      .build();

    DownloadWorker worker =
      TestWorkerBuilder.from(context, DownloadWorker.class, executor)
        .setInputData(inputData)
        .build();

    Result result = worker.doWork();
    assertThat(result, is(Result.failure()));
  }

  @Test
  public void testDownloadNoSegments() {
    DownloadWorker worker =
      TestWorkerBuilder.from(context, DownloadWorker.class, executor)
        .build();

    Result result = worker.doWork();
    assertThat(result, is(Result.failure()));
  }
}
