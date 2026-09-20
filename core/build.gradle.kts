plugins { kotlin("jvm"); `maven-publish` }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
publishing {
    publications { create<MavenPublication>("core") { from(components["java"]); artifactId = "jev-android-core" } }
    repositories { maven { name = "localBuild"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
}
