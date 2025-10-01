package com.veygax.eventhorizon.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtils {

    suspend fun isRootAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // We use "su -c" here for a quick, non-interactive check
                val process = Runtime.getRuntime().exec("su -c id")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readLine()
                process.waitFor()
                reader.close()
                process.destroy()
                output != null && output.contains("uid=0")
            } catch (e: Exception) {
                false
            }
        }
    }

    suspend fun runAsRoot(command: String, useMountMaster: Boolean = false): String {
        return withContext(Dispatchers.IO) {
            val output = StringBuilder()
            try {
                // Start an interactive 'su' shell
                val process = if (useMountMaster) {
                    Runtime.getRuntime().exec("su --mount-master")
                } else {
                    Runtime.getRuntime().exec("su")
                }

                val os = DataOutputStream(process.outputStream)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                // Write the command to the shell
                os.writeBytes("$command\n")
                os.flush()

                // Write the exit command to terminate the shell
                os.writeBytes("exit\n")
                os.flush()

                // Read the standard output
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }

                // Read the error output
                while (errorReader.readLine().also { line = it } != null) {
                    output.append("ERROR: ").append(line).append("\n")
                }

                process.waitFor()
                os.close()
                reader.close()
                errorReader.close()
                process.destroy()

            } catch (e: Exception) {
                return@withContext "Execution failed: ${e.message}"
            }
            // Return the captured output
            output.toString()
        }
    }
}