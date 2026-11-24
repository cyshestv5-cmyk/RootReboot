package com.example.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class RootShellRunner(private val context: Context) {

    @Volatile
    private var mountNsFlag: String? = null
    @Volatile
    private var mountNsProbed: Boolean = false

    private val engineFile: File
        get() = File(File(context.filesDir, "engine").apply { mkdirs() }, "userspace_reboot.sh")

    suspend fun ensureEngineScriptDeployed(): String = withContext(Dispatchers.IO) {
        val target = engineFile
        context.assets.open("userspace_reboot.sh").use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setReadable(true, true)
        target.setExecutable(true, true)
        target.absolutePath
    }

    suspend fun detectMountNsFlag(): String? = withContext(Dispatchers.IO) {
        if (mountNsProbed) return@withContext mountNsFlag
        val detected = when {
            execRaw(listOf("su", "-M", "-c", "id -u"), timeoutSec = 5).let { it.exitCode == 0 && it.stdout.trim() == "0" } -> "-M"
            execRaw(listOf("su", "-mm", "-c", "id -u"), timeoutSec = 5).let { it.exitCode == 0 && it.stdout.trim() == "0" } -> "-mm"
            else -> null
        }
        mountNsFlag = detected
        mountNsProbed = true
        detected
    }

    suspend fun runRootCommand(command: String, timeoutSec: Long = 20): ShellResult =
        withContext(Dispatchers.IO) {
            val flag = detectMountNsFlag()
            val args = if (flag != null) {
                listOf("su", flag, "-c", command)
            } else {
                listOf("su", "-c", command)
            }
            execRaw(args, timeoutSec)
        }

    private fun execRaw(commandArgs: List<String>, timeoutSec: Long): ShellResult {
        return try {
            val process = ProcessBuilder(commandArgs)
                .redirectErrorStream(false)
                .start()

            var stdout = ""
            var stderr = ""
            val outThread = Thread {
                stdout = process.inputStream.bufferedReader().use { it.readText() }
            }
            val errThread = Thread {
                stderr = process.errorStream.bufferedReader().use { it.readText() }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                outThread.join(500)
                errThread.join(500)
                return ShellResult(
                    exitCode = 124,
                    stdout = stdout,
                    stderr = if (stderr.isBlank()) "Root command timed out after ${timeoutSec}s" else stderr,
                )
            }
            outThread.join(1000)
            errThread.join(1000)
            ShellResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
            )
        } catch (t: Throwable) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = t.message ?: "Failed to invoke su binary",
            )
        }
    }
}
