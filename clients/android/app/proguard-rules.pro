# JNI entry points use conventional exported Java names, so their owning Kotlin
# classes and native method names must remain stable under release R8.
-keepclasseswithmembernames,includedescriptorclasses class com.sednalabs.solarlab.runtime.** {
    native <methods>;
}

-keepclasseswithmembernames,includedescriptorclasses class com.graciousgazelles.solarlab.feature.lab.render.** {
    native <methods>;
}
