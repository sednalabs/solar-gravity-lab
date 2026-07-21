# JNI entry points use conventional exported Java names. Keep the owning bridge
# and its native method names stable when the consuming application enables R8.
-keepclasseswithmembernames,includedescriptorclasses class com.sednalabs.solarlab.render.vulkan.** {
    native <methods>;
}
