// Constraints apply only to the buildscript/plugin classpath.
buildscript {
    dependencies {
        constraints {
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.85")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.85")
            add("classpath", "org.bouncycastle:bcutil-jdk18on:1.85")
            add("classpath", "com.google.guava:guava:33.7.2-jre")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
            add("classpath", "org.apache.commons:commons-compress:1.26.0")
            add("classpath", "org.apache.commons:commons-lang3:3.18.0")
            add("classpath", "com.google.crypto.tink:tink:1.10.0")
            add("classpath", "com.google.protobuf:protobuf-java:3.25.9")
            add("classpath", "com.google.protobuf:protobuf-java-util:3.25.9")
        }
        add("classpath", platform("io.netty:netty-bom:4.1.137.Final"))
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
