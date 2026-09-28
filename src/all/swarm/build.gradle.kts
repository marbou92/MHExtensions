import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Swarm"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.4"

    source {
        lang = "all"
        baseUrl = "https://swarm.ws"
    }

    deeplink {
        host("swarm.ws")
        path("/comic/..*")
    }
}
