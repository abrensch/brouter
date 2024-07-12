package btools.routingapp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import android.os.Build;
import android.os.Environment;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class BRouterActivityTest {
  @Rule
  public ActivityScenarioRule<BRouterActivity> rule = new ActivityScenarioRule<>(BRouterActivity.class);

  @Test
  public void storageDirectories() {
    ActivityScenario<BRouterActivity> scenario = rule.getScenario();
    scenario.onActivity(activity -> {
      List<File> storageDirectories = activity.getStorageDirectories();

      // Before Android Q (10) legacy storage access is possible
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        assertThat(storageDirectories, hasItem(Environment.getExternalStorageDirectory()));
      }

      // When targeting older SDK we can access legacy storage on any android version
      if (activity.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.Q) {
        assertThat(storageDirectories, hasItem(Environment.getExternalStorageDirectory()));
      }

      assertThat(storageDirectories, not(empty()));
    });
  }

}
