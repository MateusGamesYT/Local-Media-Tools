package androidx.test.espresso;
import java.util.*;
public final class IdlingRegistry { private static final IdlingRegistry I = new IdlingRegistry();
  public static IdlingRegistry getInstance() { return I; }
  public Collection<IdlingResource> getResources() { return Collections.emptyList(); }
  public Collection<android.os.Looper> getLoopers() { return Collections.emptyList(); } }
