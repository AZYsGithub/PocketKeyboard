package com.pocketkeyboard.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Provides root shell access via the `su` binary.
 * All operations that interact with USB gadget ConfigFS require root.
 */
object RootShell {

    /**
     * Check whether root (su) is available on this device.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()
            val exitCode = process.waitFor()
            os.close()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute a single command as root and return stdout.
     */
    suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val output = reader.readText()
            val error = errorReader.readText()
            val exitCode = process.waitFor()

            os.close()
            reader.close()
            errorReader.close()

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(Exception("Command failed (exit $exitCode): $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Execute multiple commands as root in a single su session.
     */
    suspend fun executeBatch(commands: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            for (cmd in commands) {
                os.writeBytes("$cmd\n")
            }
            os.writeBytes("exit\n")
            os.flush()

            val output = reader.readText()
            val error = errorReader.readText()
            val exitCode = process.waitFor()

            os.close()
            reader.close()
            errorReader.close()

            if (exitCode == 0) {
                Result.success(output.trim())
            } else {
                Result.failure(Exception("Batch failed (exit $exitCode): $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
