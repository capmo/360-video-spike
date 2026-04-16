pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Credentials for Insta360's Maven repo ship publicly in the SDK zip, so "secret"
// is a misnomer — but Capmo engineers should still source them from local.properties
// / env vars, not from this file. Publication defaults mirror the demo creds so a
// fresh clone builds without any setup.
val instaMavenUser: String = providers.gradleProperty("insta360.maven.user")
    .orElse(providers.environmentVariable("INSTA360_MAVEN_USER"))
    .getOrElse("insta360guest")
val instaMavenPass: String = providers.gradleProperty("insta360.maven.password")
    .orElse(providers.environmentVariable("INSTA360_MAVEN_PASSWORD"))
    .getOrElse("EXMSjSo8OeOrjU7d")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Insta360 Mobile SDK — public repo, credentials from local.properties
        // (insta360.maven.user / insta360.maven.password) or env vars, falling
        // back to the demo creds published in the SDK distribution zip.
        maven {
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            isAllowInsecureProtocol = true
            credentials {
                username = instaMavenUser
                password = instaMavenPass
            }
        }
    }
}

rootProject.name = "insta360-spike"
include(":app")
include(":viewer")
