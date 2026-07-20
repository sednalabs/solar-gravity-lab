# Native Vulkan entry points are resolved by their exported JNI names.
-keepclasseswithmembernames,includedescriptorclasses class com.graciousgazelles.solarlab.feature.lab.render.** {
    native <methods>;
}
