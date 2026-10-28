plugins {
    kotlin("jvm") version "2.1.21"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("io.vertx:vertx-web:4.5.26")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.4")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("com.openai:openai-java:4.93.0")
    implementation("com.knuddels:jtokkit:1.1.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
kotlin { jvmToolchain(21) }
sourceSets {
    main {
        kotlin.srcDirs("server/src/main/kotlin")
        resources.srcDirs("server/src/main/resources")
    }
    test {
        kotlin.srcDirs("tests/kotlin")
        resources.srcDirs("tests/fixtures")
    }
}
application { mainClass.set("reviq.MainKt") }
tasks.test { useJUnitPlatform() }
