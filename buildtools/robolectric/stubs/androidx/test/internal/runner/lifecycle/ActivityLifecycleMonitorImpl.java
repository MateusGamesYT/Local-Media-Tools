package androidx.test.internal.runner.lifecycle;
import androidx.test.runner.lifecycle.*; import android.app.Activity; import java.util.*;
public final class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
  private final WeakHashMap<Activity, Stage> stages = new WeakHashMap<>();
  public ActivityLifecycleMonitorImpl() {}
  public ActivityLifecycleMonitorImpl(boolean b) {}
  public void signalLifecycleChange(Stage s, Activity a) { stages.put(a, s); }
  public Stage getLifecycleStageOf(Activity a) { Stage s = stages.get(a); if (s == null) throw new IllegalArgumentException("Unknown activity"); return s; }
}
