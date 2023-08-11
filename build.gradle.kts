import nebula.plugin.contacts.Contact
import nebula.plugin.contacts.ContactsExtension

plugins {
    `java-library`

    id("nebula.release") version "16.0.0"

    id("nebula.maven-manifest") version "18.4.0"
    id("nebula.maven-nebula-publish") version "18.4.0"
    id("nebula.maven-resolved-dependencies") version "18.4.0"

    id("nebula.contacts") version "6.0.0"
    id("nebula.info") version "11.3.3"

    id("nebula.javadoc-jar") version "18.4.0"
    id("nebula.source-jar") version "18.4.0"
}

apply(plugin = "nebula.publish-verification")

configure<nebula.plugin.release.git.base.ReleasePluginExtension> {
    defaultVersionStrategy = nebula.plugin.release.NetflixOssStrategies.SNAPSHOT(project)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

group = "io.moderne"
description = "Rewrite AI search."

repositories {
    mavenLocal()
    // Needed to pick up snapshot versions of rewrite
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        cacheChangingModulesFor(0, TimeUnit.SECONDS)
        cacheDynamicVersionsFor(0, TimeUnit.SECONDS)
    }
}

// The bom version can also be set to a specific version or latest.release.
val rewriteBomVersion = "latest.integration"

dependencies {
    compileOnly("org.projectlombok:lombok:latest.release")
    compileOnly("com.google.code.findbugs:jsr305:latest.release")
    annotationProcessor("org.projectlombok:lombok:latest.release")
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:${rewriteBomVersion}"))
    implementation("org.openrewrite:rewrite-core")
    implementation("org.openrewrite:rewrite-java")

    implementation("io.micrometer:micrometer-core:latest.release")
    implementation("io.github.resilience4j:resilience4j-retry:latest.release")
    implementation("com.konghq:unirest-java:3.14.2")

    testImplementation("org.junit.jupiter:junit-jupiter-api:latest.release")
    testImplementation("org.junit.jupiter:junit-jupiter-params:latest.release")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:latest.release")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testImplementation("com.squareup.okhttp3:okhttp:latest.release")

    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.assertj:assertj-core:latest.release")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    jvmArgs = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

configure<ContactsExtension> {
    val j = Contact("team@moderne.io")
    j.moniker("Team Moderne")
    people["team@moderne.io"] = j
}

configure<PublishingExtension> {
    publications {
        named("nebula", MavenPublication::class.java) {
            suppressPomMetadataWarningsFor("runtimeElements")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "moderne"
            url = uri("https://us-west1-maven.pkg.dev/moderne-dev/moderne-recipe")
        }
    }
}
