# JNI entry points use conventional exported Java names, so their owning Kotlin
# classes and native method names must remain stable under release R8.
-keepclasseswithmembernames,includedescriptorclasses class com.sednalabs.solarlab.runtime.** {
    native <methods>;
}

# The Rust FFI builds these payloads with hard-coded JVM class names and
# constructor descriptors. Keeping only the JNI entry-point owner above is not
# enough: R8 may rename a DTO such as NativeResult, which makes native session
# creation return null after the library has loaded. Preserve every native DTO
# class and its constructors in minified phone builds.
-keep class com.sednalabs.solarlab.runtime.Native* {
    <init>(...);
}
