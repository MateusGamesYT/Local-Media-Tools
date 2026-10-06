package androidx.test.runner.intent;
public final class IntentMonitorRegistry { private static IntentMonitor m; public static void registerInstance(IntentMonitor x) { m = x; } public static IntentMonitor getInstance() { return m; } }
