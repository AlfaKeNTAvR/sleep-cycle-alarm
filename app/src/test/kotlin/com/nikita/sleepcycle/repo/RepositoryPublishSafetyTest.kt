package com.nikita.sleepcycle.repo

// File purpose: the guard that keeps the owner's own identifiers out of the published repository.
//
// Before the first push this repo carried the band's real Bluetooth MAC, the phone's device serial, the band's
// unit name and a hardcoded Windows home directory, in the working tree and in all 17 commits. Scrubbing them
// was a one-off; this test is what stops them coming back.
//
// It matches SHAPES, never the removed values: a test that hardcoded the real MAC in order to search for it
// would itself be the leak. Matching shapes also catches identifiers nobody has leaked yet - a different band,
// a second phone, another machine's home directory.
//
// Scope: every file `git ls-files` reports, which is exactly what a push would publish. It does not re-read
// history, so it prevents a new leak rather than finding an old one; history was verified clean by hand at the
// time of the scrub.

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * This file is the one tracked file the scan skips. Its own comments and failure messages have to spell out the
 * shapes it looks for, so scanning it would report itself on every run and nothing else would ever be visible.
 */
private const val THIS_FILE_NAME = "RepositoryPublishSafetyTest.kt"

/** A single match: where it is and what matched, phrased so the failure message alone is enough to act on. */
private data class Leak(val path: String, val lineNumber: Int, val line: String) {
    override fun toString(): String = "$path:$lineNumber: ${line.trim().take(160)}"
}

/** The one repository root, found by walking up from wherever the test runner started. */
private val repositoryRoot: File by lazy {
    val startingDirectory = System.getProperty("user.dir") ?: error("the test runner set no working directory")
    generateSequence(File(startingDirectory).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("no repository root above $startingDirectory: expected a settings.gradle.kts")
}

/**
 * Every tracked file, as lines of text, skipping the ones that are not text at all. Asking git rather than
 * walking the directory is deliberate: ignored files (local.properties, a pulled night log, build output)
 * legitimately hold personal data and are never published, so they must not fail this test.
 */
private val trackedTextFiles: List<Pair<String, List<String>>> by lazy {
    val process = ProcessBuilder("git", "ls-files").directory(repositoryRoot).redirectErrorStream(true).start()
    val paths = process.inputStream.bufferedReader().readLines()
    check(process.waitFor() == 0) { "git ls-files failed in $repositoryRoot" }
    paths.filter { it.isNotBlank() && !it.endsWith(THIS_FILE_NAME) }.mapNotNull { path ->
        val file = File(repositoryRoot, path)
        if (!file.isFile) return@mapNotNull null
        val bytes = file.readBytes()
        if (bytes.contains(0)) return@mapNotNull null
        path to bytes.toString(Charsets.UTF_8).lines()
    }
}

/** Every tracked line matching [pattern] whose matched text is not in [allowed]. */
private fun findLeaks(pattern: Regex, allowed: Set<String> = emptySet()): List<Leak> =
    trackedTextFiles.flatMap { (path, lines) ->
        lines.withIndex()
            .filter { (_, line) -> pattern.findAll(line).any { it.value.uppercase() !in allowed } }
            .map { (index, line) -> Leak(path, index + 1, line) }
    }

private fun assertNoLeaks(leaks: List<Leak>, remedy: String) {
    assertTrue(leaks.isEmpty()) { remedy + "\n" + leaks.joinToString("\n") }
}

class RepositoryPublishSafetyTest {
    /**
     * Six colon-separated hex pairs. The placeholders the other tests use are listed here so that a real
     * address, which will not be one of them, stands out; adding a new placeholder is the only way to pass.
     */
    @Test
    fun `no tracked file carries a real Bluetooth MAC address`() {
        val placeholders = setOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66", "00:00:00:00:00:00")
        val anyMacAddress = Regex("""\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\b""")

        assertNoLeaks(
            findLeaks(anyMacAddress, placeholders),
            "A real device MAC is about to be published. Use one of $placeholders instead:",
        )
    }

    /**
     * Any per-machine user directory, in either slash spelling. Scripts belong on the HOME variable and prose
     * on a tilde, both of which are true on every machine rather than only on the author's.
     */
    @Test
    fun `no tracked file carries a home directory path`() {
        val anyUserDirectory = Regex("""Users[\\/][A-Za-z0-9._-]+""")

        assertNoLeaks(
            findLeaks(anyUserDirectory),
            "A machine-specific home directory is about to be published. Use \$HOME in scripts and ~ in prose:",
        )
    }

    /**
     * A device serial passed to the Android debug bridge names one physical phone. With a single device
     * attached the flag is unnecessary, so it has no place in a published instruction.
     */
    @Test
    fun `no tracked file carries a device serial`() {
        val bridgeCommandNamingADevice = Regex("""\badb\b[^\n]*?\s-s\s+\S+""")

        assertNoLeaks(
            findLeaks(bridgeCommandNamingADevice),
            "A published command names one physical device. Drop the -s flag; it defaults to the attached device:",
        )
    }

    /**
     * The ignore rules that keep health data and signing keys untracked in the first place. Without this, the
     * three tests above would still pass on the day a line is dropped from the ignore file, right up until the
     * file it was covering gets committed.
     */
    @Test
    fun `the ignore file still covers health data and signing keys`() {
        val ignoreRules = File(repositoryRoot, ".gitignore").readLines().map { it.trim() }
        val required = listOf("*.db", "*.jsonl", "*.csv", "*.jks", "*.keystore", "google-services.json", ".env", "local.properties")

        val missing = required.filterNot { it in ignoreRules }
        assertTrue(missing.isEmpty()) { "the ignore file no longer covers $missing, so those files can be committed by accident" }
    }
}
