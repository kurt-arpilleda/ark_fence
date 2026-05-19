package com.example.arkfence

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Handler
import android.widget.Toast
import androidx.core.content.FileProvider
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.pow

class AppUpdateService(private val context: Context) {

    private val service: ApiService = RetrofitClient.instance
    private var progressDialog: ProgressDialog? = null
    private val handler = Handler(context.mainLooper)
    private val MAX_RETRIES = 10
    private val INITIAL_RETRY_DELAY_MS = 1000L // 1 second

    fun checkForAppUpdate() {
        val currentVersionCode = getCurrentAppVersionCode()

        service.getAppUpdateDetails().enqueue(object : Callback<AppUpdateResponse> {
            override fun onResponse(call: Call<AppUpdateResponse>, response: Response<AppUpdateResponse>) {
                if (response.isSuccessful) {
                    val appUpdateResponse = response.body()

                    if (appUpdateResponse?.elements?.isNotEmpty() == true) {
                        val newVersionCode = appUpdateResponse.elements[0].versionCode

                        if (newVersionCode > currentVersionCode) {
                            startAutomaticUpdate(appUpdateResponse.elements[0].outputFile)
                        }
                    }
                }
            }

            override fun onFailure(call: Call<AppUpdateResponse>, t: Throwable) {
                // Handle network failures here
                Toast.makeText(context, "Failed to check for updates", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun getCurrentAppVersionCode(): Int {
        return try {
            val packageInfo: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionCode
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            -1
        }
    }

    private fun startAutomaticUpdate(fileName: String) {
        showDownloadProgressDialog(fileName)
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        downloadFileWithRetry(fileName, destinationFile, 0)
    }

    private fun showDownloadProgressDialog(fileName: String) {
        progressDialog = ProgressDialog(context).apply {
            setTitle("Updating App")
            setMessage("Downloading new version...")
            setCancelable(false)
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setIndeterminate(false)
        }
        progressDialog?.show()
    }

    private fun downloadFileWithRetry(fileName: String, destinationFile: File, attempt: Int) {
        // Choose URL based on attempt (alternate between primary and fallback)
        val baseUrl = if (attempt % 2 == 0) RetrofitClient.PRIMARY_URL else RetrofitClient.FALLBACK_URL
        val url = "$baseUrl/V4/Others/Kurt/LatestVersionAPK/ArkFence/$fileName"

        Thread {
            var downloadedBytes: Long = 0
            var totalBytes: Long = 0
            var connection: HttpURLConnection? = null

            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000 // 2 seconds timeout
                connection.readTimeout = 2000 // 2 seconds timeout
                connection.connect()

                totalBytes = connection.contentLength.toLong()
                handler.post {
                    progressDialog?.max = totalBytes.toInt()
                }

                val inputStream: InputStream = BufferedInputStream(connection.inputStream)
                val outputStream = FileOutputStream(destinationFile)
                val buffer = ByteArray(8192)
                var count: Int
                while (inputStream.read(buffer).also { count = it } != -1) {
                    outputStream.write(buffer, 0, count)
                    downloadedBytes += count.toLong()

                    // Update progress on the main thread
                    handler.post {
                        progressDialog?.progress = downloadedBytes.toInt()
                        progressDialog?.setMessage("Downloading... ${(downloadedBytes * 100) / totalBytes}%")
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                handler.post {
                    progressDialog?.dismiss()
                    installAPK(destinationFile)
                }

            } catch (e: Exception) {
                connection?.disconnect()

                if (attempt < MAX_RETRIES - 1) {
                    // Calculate exponential backoff delay
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt.toDouble())).toLong()

                    handler.post {
                        progressDialog?.setMessage("Retrying download... (Attempt ${attempt + 1}/$MAX_RETRIES)")
                    }

                    try {
                        Thread.sleep(delayMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }

                    // Retry with next attempt
                    downloadFileWithRetry(fileName, destinationFile, attempt + 1)
                } else {
                    handler.post {
                        progressDialog?.dismiss()
                        Toast.makeText(context, "Update failed. Please try again later.", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun installAPK(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Installation failed: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
}