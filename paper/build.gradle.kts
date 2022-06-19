plugins {
    id("io.papermc.paperweight.userdev")
    id("com.github.johnrengelman.shadow")
    id("xyz.jpenilla.run-paper")
}

dependencies {
    paperDevBundle("1.18.2-R0.1-SNAPSHOT")

    compileOnly("net.civmc.zeus:zeus:2.0.0-SNAPSHOT")
    compileOnly("net.civmc.civmodcore:civmodcore-paper:2.2.0")
}
