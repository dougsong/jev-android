plugins { id("com.android.library"); kotlin("android"); `maven-publish` }
android {
    namespace = "io.github.jevandroid"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
afterEvaluate {
    publishing {
        publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "jev-android" } }
        repositories { maven { name = "localBuild"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
    }
}
