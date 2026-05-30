package com.example.arkfence

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.os.Handler
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
    private var updateDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var percentText: TextView? = null
    private val handler = Handler(context.mainLooper)
    private val MAX_RETRIES = 10
    private val INITIAL_RETRY_DELAY_MS = 1000L
    private val PROGRESS_UPDATE_INTERVAL_MS = 200L
    private var lastProgressUpdateTime = 0L

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
        showDownloadProgressDialog()
        val destinationFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        downloadFileWithRetry(fileName, destinationFile, 0)
    }

    private fun showDownloadProgressDialog() {
        val dp = context.resources.displayMetrics.density

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        val titleText = TextView(context).apply {
            text = "Updating ArkFence"
            textSize = 17f
            setTextColor(Color.parseColor("#E0E0FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * dp).toInt())
        }

        val subtitleText = TextView(context).apply {
            text = "Please keep the app open"
            textSize = 12f
            setTextColor(Color.parseColor("#888AAA"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (20 * dp).toInt())
        }

        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = buildProgressDrawable(dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * dp).toInt()
            )
        }
        progressBar = bar

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, 0)
        }

        val statusTv = TextView(context).apply {
            text = "Connecting..."
            textSize = 12f
            setTextColor(Color.parseColor("#AAAACC"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusText = statusTv

        val percentTv = TextView(context).apply {
            text = "0%"
            textSize = 13f
            setTextColor(Color.parseColor("#7C83FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        percentText = percentTv

        row.addView(statusTv)
        row.addView(percentTv)

        root.addView(titleText)
        root.addView(subtitleText)
        root.addView(bar)
        root.addView(row)

        val dialogBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#1E1E2E"))
            cornerRadius = 20 * dp
        }

        updateDialog = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(false)
            .create()

        updateDialog?.show()
        updateDialog?.window?.setBackgroundDrawable(dialogBackground)
    }

    private fun buildProgressDrawable(dp: Float): android.graphics.drawable.LayerDrawable {
        val bgShape = GradientDrawable().apply {
            setColor(Color.parseColor("#2E2E45"))
            cornerRadius = 10 * dp
        }
        val fillShape = GradientDrawable().apply {
            val colors = intArrayOf(Color.parseColor("#6B6FF5"), Color.parseColor("#9B59FF"))
            setColors(colors)
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            cornerRadius = 10 * dp
        }
        val bgLayer = android.graphics.drawable.ClipDrawable(
            bgShape, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        val fillClip = android.graphics.drawable.ClipDrawable(
            fillShape, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL
        )
        return android.graphics.drawable.LayerDrawable(arrayOf(bgShape, fillClip)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun downloadFileWithRetry(fileName: String, destinationFile: File, attempt: Int) {
        val baseUrl = if (attempt % 2 == 0) RetrofitClient.PRIMARY_URL else RetrofitClient.FALLBACK_URL
        val url = "$baseUrl/V4/Others/Kurt/LatestVersionAPK/ArkFence/$fileName"

        Thread {
            var downloadedBytes = 0L
            var totalBytes = 0L
            var connection: HttpURLConnection? = null
            lastProgressUpdateTime = 0L

            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.connect()

                totalBytes = connection.contentLength.toLong()

                val inputStream: InputStream = BufferedInputStream(connection.inputStream)
                val outputStream = FileOutputStream(destinationFile)
                val buffer = ByteArray(8192)
                var count: Int

                while (inputStream.read(buffer).also { count = it } != -1) {
                    outputStream.write(buffer, 0, count)
                    downloadedBytes += count.toLong()

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdateTime >= PROGRESS_UPDATE_INTERVAL_MS) {
                        lastProgressUpdateTime = now
                        val captured = downloadedBytes
                        val total = totalBytes
                        handler.post {
                            val percent = if (total > 0) ((captured * 100) / total).toInt() else 0
                            progressBar?.progress = percent
                            percentText?.text = "$percent%"
                            statusText?.text = "Downloading... ${formatBytes(captured)} / ${formatBytes(total)}"
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                handler.post {
                    progressBar?.progress = 100
                    percentText?.text = "100%"
                    statusText?.text = "Installing..."
                }

                handler.postDelayed({
                    updateDialog?.dismiss()
                    installAPK(destinationFile)
                }, 600)

            } catch (e: Exception) {
                connection?.disconnect()

                if (attempt < MAX_RETRIES - 1) {
                    val delayMs = INITIAL_RETRY_DELAY_MS * (2.0.pow(attempt.toDouble())).toLong()
                    val nextAttempt = attempt + 1

                    handler.post {
                        statusText?.text = "Retrying... ($nextAttempt/$MAX_RETRIES)"
                        percentText?.text = ""
                        progressBar?.progress = 0
                    }

                    try {
                        Thread.sleep(delayMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }

                    downloadFileWithRetry(fileName, destinationFile, nextAttempt)
                } else {
                    handler.post {
                        updateDialog?.dismiss()
                        Toast.makeText(context, "Update failed. Please try again later.", Toast.LENGTH_SHORT).show()
                    }
                    e.printStackTrace()
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
            else -> "$bytes B"
        }
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
