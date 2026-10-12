# Rules the dependencies do not ship themselves. Clerk, Room, WorkManager, kotlinx.serialization
# and the Compose/AndroidX libraries carry consumer rules and need nothing here.

# convex-mobile ships no consumer rules. Its uniffi bindings are mapped onto the native library by
# JNA, which reads Structure fields, Callback methods and Library interface methods by name via
# reflection and calls back into Kotlin from native code, so none of it may be renamed or removed.
-keep class dev.convex.android.** { *; }

# JNA's AAR ships no consumer rules; its native dispatch library (libjnidispatch.so) looks up these
# classes and their fields and methods by name through JNI.
-keep class com.sun.jna.** { *; }

# JNA's classes.jar refers to java.awt (Window, Component and so on), which does not exist on Android.
-dontwarn java.awt.**
