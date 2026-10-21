plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
}

// --- FLEET-2: build identity, computed once, read by both modules ---------
// Every value fails toward "untrusted" (unknown sha / dirty), never toward a
// confident-but-wrong "clean known build", so the fleet monitor can never
// mistake a build it can't identify for a current one.
fun gitValue(vararg args: String): String? =
    try {
        val exec =
            providers.exec {
                workingDir = rootDir
                isIgnoreExitValue = true
                commandLine(listOf("git") + args)
            }
        if (exec.result.get().exitValue == 0) exec.standardOutput.asText.get().trim() else null
    } catch (e: Exception) {
        null
    }

val zodiacVersionBase = "0.1.0"
val zodiacGitSha =
    gitValue("rev-parse", "--short=9", "HEAD")
        ?.takeIf { Regex("[0-9a-f]{7,40}").matches(it) }
        ?: "unknown"
val zodiacGitDirty = gitValue("status", "--porcelain")?.isNotBlank() ?: true
val zodiacCommitEpoch = gitValue("log", "-1", "--format=%ct", "HEAD")?.toLongOrNull() ?: 0L

extra["zodiacVersionBase"] = zodiacVersionBase
extra["zodiacGitSha"] = zodiacGitSha
extra["zodiacGitDirty"] = zodiacGitDirty
extra["zodiacCommitEpoch"] = zodiacCommitEpoch

// --- HOME: the vehicle's home camp, read by both modules ------------------
// The real camp lives only in the gitignored local.properties, so the public
// tree carries nothing but the neutral default (7:30 & E on the 2026 grid).
// Absent keys → the default; a key that is present but malformed fails the
// build, because a silently-defaulted HOME would send the whole fleet to the
// wrong place with nothing on screen to say so.
//   zodiac.home.lat=40.7…   zodiac.home.lon=-119.1…
val zodiacLocalProps =
    java.util.Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

fun homeCoord(
    key: String,
    default: Double,
    range: ClosedFloatingPointRange<Double>,
): Double {
    val raw = zodiacLocalProps.getProperty(key) ?: return default
    val v = raw.trim().toDoubleOrNull() ?: throw GradleException("local.properties: $key='$raw' is not a number")
    if (v !in range) throw GradleException("local.properties: $key=$v is outside $range (Black Rock Desert)")
    return v
}

if ((zodiacLocalProps.getProperty("zodiac.home.lat") == null) != (zodiacLocalProps.getProperty("zodiac.home.lon") == null)) {
    throw GradleException("local.properties: set both zodiac.home.lat and zodiac.home.lon, or neither")
}
extra["zodiacHomeLat"] = homeCoord("zodiac.home.lat", 40.7832474, 40.6..41.0)
extra["zodiacHomeLon"] = homeCoord("zodiac.home.lon", -119.2225761, -119.4..-119.0)
