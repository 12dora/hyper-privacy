# The Xposed framework instantiates the entry listed in META-INF/xposed/java_init.list by name
# and calls its lifecycle overrides; libxposed is compileOnly, so R8 cannot see those calls.
-keep class io.github.hyperprivacy.xposed.ModuleMain {
    public <init>();
    public *** on*(...);
}

# Provided by the framework at runtime (compileOnly).
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**

# Hookers are invoked by the framework through the (compileOnly) Hooker interface.
-keep,allowobfuscation class * implements io.github.libxposed.api.XposedInterface$Hooker {
    public java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

# Every class R8 may rename goes into this one package. Stack scrubbing decides "module frame" by
# this prefix alone (ModuleFrames), so it must not collide with a hooked app's classes: no real
# package uses it, and R8 only reaches four-character names past hundreds of thousands of packages.
# Keep it neutral (it shows in nothing but our own frames, which are scrubbed).
-repackageclasses 'u_qz'

# ModuleFrames reads the package above from its own runtime name: never merge or inline it away,
# but let it be renamed (and so repackaged). The release build checks the mapping for this
# (checkReleaseModulePackage in app/build.gradle.kts).
-keep,allowobfuscation class io.github.hyperprivacy.xposed.ModuleFrames

# JNI entry points of libhpnative.so are bound by name (Java_io_github_hyperprivacy_xposed_NativeResolver_*).
# Kept under the module's source root, whose frames stack scrubbing hides like repackaged ones.
-keep class io.github.hyperprivacy.xposed.NativeResolver {
    native <methods>;
}
