plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.liuchong.tunar.corebinding"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            // Kotlin 源码同时放在 src/main/kotlin（含 generated/ 下 UniFFI 生成代码）
            java.srcDir("src/main/kotlin")
            // .so 由 scripts/build-core-android.sh 输出到 src/main/jniLibs
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

// 可选钩子：取消注释后，每次构建自动重编 Rust core（IDE 场景较慢，默认关闭）。
// 手动执行：scripts/build-core-android.sh
// tasks.register("buildRustCore", Exec) {
//     workingDir = rootDir.parentFile
//     commandLine("scripts/build-core-android.sh")
// }
// tasks.named("preBuild") { dependsOn("buildRustCore") }

dependencies {
    // UniFFI Kotlin 绑定运行时需要 JNA（Android AAR 版）
    api("net.java.dev.jna:jna:5.14.0@aar")
}
