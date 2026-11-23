plugins {
    id("com.android.application")
}

dependencies {
    implementation("androidx.activity:activity:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
}

android {
    namespace = "com.daybook.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.daybook.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        val apiUrl = providers.gradleProperty("DAYBOOK_API_BASE_URL")
            .orElse(providers.environmentVariable("DAYBOOK_API_BASE_URL")).orNull ?: ""
        require(apiUrl.isEmpty() || Regex("https://[A-Za-z0-9.:-]+/?").matches(apiUrl)) {
            "DAYBOOK_API_BASE_URL must be an HTTPS origin"
        }
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
    }

    buildFeatures { buildConfig = true }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
