package androidx.test.runner.lifecycle;
public interface ActivityLifecycleMonitor {
  Stage getLifecycleStageOf(android.app.Activity a);
  default void addLifecycleCallback(ActivityLifecycleCallback c) {}
  default void removeLifecycleCallback(ActivityLifecycleCallback c) {}
  default java.util.Collection<android.app.Activity> getActivitiesInStage(Stage s) { return java.util.Collections.emptyList(); }
}
