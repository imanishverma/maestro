package util

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.utils.MaestroTimer
import net.harawata.appdirs.AppDirsFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object XCRunnerCLIUtils {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private const val MAX_COUNT_XCTEST_LOGS = 5

    private val dateFormatter by lazy { DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT) }
    private val logDirectory by lazy {
        val parentName = AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR)
        val logsDirectory = File(parentName, "xctest_runner_logs")
        File(parentName).apply {
            if (!exists()) mkdir()

            if (!logsDirectory.exists()) logsDirectory.mkdir()

            val existing = logsDirectory.listFiles() ?: emptyArray()
            val toDelete = existing.sortedByDescending { it.name }
            val count = toDelete.size
            if (count > MAX_COUNT_XCTEST_LOGS) toDelete.forEach { it.deleteRecursively() }
        }
        logsDirectory
    }

    fun listApps(): Set<String> {
        val process = Runtime.getRuntime().exec(arrayOf("bash", "-c", "xcrun simctl listapps booted | plutil -convert json - -o -"))

        val json = String(process.inputStream.readBytes())

        if (json.isEmpty()) return emptySet()

        val mapper = jacksonObjectMapper()
        val appsMap = mapper.readValue(json, Map::class.java) as Map<String, Any>

        return appsMap.keys
    }

    fun setProxy(host: String, port: Int) {
        ProcessBuilder("networksetup", "-setwebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun resetProxy() {
        ProcessBuilder("networksetup", "-setwebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    fun uninstall(bundleId: String) {
        CommandLineUtils.runCommand(
            listOf(
                "xcrun",
                "simctl",
                "uninstall",
                "booted",
                bundleId
            )
        )
    }

    fun ensureAppAlive(bundleId: String) {
        MaestroTimer.retryUntilTrue(timeoutMs = 4000, delayMs = 300) {
            isAppAlive(bundleId)
        }
    }

    private fun runningApps(): Map<String, Int?> {
        val process = ProcessBuilder(
            "xcrun",
            "simctl",
            "spawn",
            "booted",
            "launchctl",
            "list"
        ).start()

        val processOutput = process.inputStream
            .bufferedReader()
            .readLines()

        process.waitFor(3000, TimeUnit.MILLISECONDS)

        return processOutput
            .asSequence()
            .drop(1)
            .toList()
            .map { line -> line.split("\\s+".toRegex()) }
            .filter { parts -> parts.count() <= 3 }
            .associate { parts -> parts[2] to parts[0].toIntOrNull() }
            .mapKeys { (key, _) ->
                // Fixes issue with iOS 14.0 where process names are sometimes prefixed with "UIKitApplication:"
                // and ending with [stuff]
                key
                    .substringBefore("[")
                    .replace("UIKitApplication:", "")
            }
    }

    fun isAppAlive(bundleId: String): Boolean {
        return runningApps()
            .containsKey(bundleId)
    }

    fun pidForApp(bundleId: String): Int? {
        return runningApps()[bundleId]
    }

    fun runXcTestWithoutBuild(deviceId: String, xcTestRunFilePath: String): Process {
        val date = dateFormatter.format(LocalDateTime.now())
        return CommandLineUtils.runCommand(
            listOf(
                "xcodebuild",
                "test-without-building",
                "-xctestrun",
                xcTestRunFilePath,
                "-destination",
                "id=$deviceId",
            ),
            waitForCompletion = false,
            outputFile = File(logDirectory, "xctest_runner_$date.log")
        )
    }

    fun screenshot(path: String) {
        CommandLineUtils.runCommand(
            listOf("xcrun", "simctl", "io", "booted", "screenshot", path),
            waitForCompletion = true
        )
    }
}
