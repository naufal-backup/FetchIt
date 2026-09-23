package com.tharunbirla.fetchit

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Patterns
import android.view.View
import android.webkit.URLUtil
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.fetchit.utils.DirectFileFetcher
import com.tharunbirla.fetchit.utils.DownloadHistory
import com.tharunbirla.fetchit.utils.FacebookUrlFetcher
import com.tharunbirla.fetchit.utils.HistoryEntry
import com.tharunbirla.fetchit.utils.InstagramUrlFetcher
import com.tharunbirla.fetchit.utils.ThemeHelper
import com.tharunbirla.fetchit.utils.TwitterUrlFetcher
import com.tharunbirla.fetchit.utils.YouTubeUrlFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private val PROGRESS_NOTIFICATION_ID = 100
    private val COMPLETION_NOTIFICATION_ID = 101
    private var isDownloadStarted = false
    private lateinit var requestPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private val channelId = "download_channel"
    private lateinit var saveLocationLauncher: ActivityResultLauncher<Intent>
    private var downloadJob: Job? = null
    private var pendingFileName: String = ""
    private var lastProgressShown = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applySaved(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        registerPermissionLauncher()
        if (!arePermissionsGranted()) {
            showPermissionRequestDialog()
        }
        setupUI()
        refreshHistory()
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the activity's intent
        setIntent(intent)
        // Handle the new intent
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    handleSharedText(intent)
                }
            }
            Intent.ACTION_VIEW -> {
                handleViewAction(intent)
            }
        }
    }

    private fun handleViewAction(intent: Intent) {
        intent.data?.toString()?.let { urlString ->
            if (isValidUrl(urlString)) {
                findViewById<TextInputEditText>(R.id.urlInput).setText(urlString)
                findViewById<MaterialButton>(R.id.downloadButton).performClick()
            } else {
                showToast("Invalid URL format")
            }
        }
    }

    private fun handleSharedText(intent: Intent) {
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
            // Try to extract URL from shared text
            val urls = extractUrls(sharedText)
            if (urls.isNotEmpty()) {
                val url = urls.first()
                if (isValidUrl(url)) {
                    findViewById<TextInputEditText>(R.id.urlInput).setText(url)
                    // Automatically trigger the download button
                    findViewById<MaterialButton>(R.id.downloadButton).performClick()
                } else {
                    showToast("Invalid URL format")
                }
            } else {
                showToast("No valid URL found")
            }
        }
    }

    private fun isValidUrl(urlString: String): Boolean {
        return try {
            // First check using Android's URLUtil
            if (!URLUtil.isValidUrl(urlString)) {
                return false
            }

            // Additional validation by attempting to create a URL object
            val url = URL(urlString)

            // Check if the URL has a protocol and host
            url.protocol.isNotEmpty() && url.host.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun extractUrls(text: String): List<String> {
        // Simple URL extraction using regex
        val urlRegex = Patterns.WEB_URL.pattern().toRegex()
        return urlRegex.findAll(text)
            .map { it.value }
            .filter { isValidUrl(it) }
            .toList()
    }

    /** Daftarkan permission launcher di semua API level (dipanggil dari onCreate). */
    private fun registerPermissionLauncher() {
        requestPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val allGranted = permissions.values.all { it }
                if (allGranted) {
                    showToast("All permissions granted")
                } else {
                    handleDeniedPermissions(permissions)
                }
            }
    }

    private fun handleDeniedPermissions(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys

        if (deniedPermissions.isNotEmpty()) {
            val permanentlyDenied = deniedPermissions.any { permission ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    !shouldShowRequestPermissionRationale(permission)
                } else {
                    false
                }
            }

            if (permanentlyDenied) {
                showSettingsDialog()
            } else {
                showPermissionExplanationDialog(deniedPermissions.toTypedArray())
            }
        }
    }

    private fun showPermissionExplanationDialog(permissions: Array<String>) {
        val permissionNames = permissions.joinToString("\n") { permission ->
            when (permission) {
                Manifest.permission.POST_NOTIFICATIONS -> "• Notifications"
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "• Storage access"
                else -> "• $permission"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("The following permissions are needed for full functionality:\n\n$permissionNames")
            .setPositiveButton("Try Again") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showToast("Some features may be limited")
            }
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Some permissions are permanently denied. Please enable them in Settings to use all features.")
            .setPositiveButton("Go to Settings") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                showToast("Some features may be limited")
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            startActivity(this)
        }
    }

    private fun setupUI() {
        val backgroundColor = ContextCompat.getColor(this, R.color.background)
        window.statusBarColor = backgroundColor

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_theme) {
                showThemeDialog()
                true
            } else {
                false
            }
        }

        val urlInput = findViewById<TextInputEditText>(R.id.urlInput)
        val downloadButton = this.findViewById<MaterialButton>(R.id.downloadButton)
        val copyActionButton = findViewById<FloatingActionButton>(R.id.copyButton)
        val fileNameInput = findViewById<TextInputEditText>(R.id.fileNameInput)
        val detectText = findViewById<TextView>(R.id.detectText)
        val cancelButton = findViewById<MaterialButton>(R.id.cancelButton)
        val clearHistoryButton = findViewById<MaterialButton>(R.id.clearHistoryButton)

        saveLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { uri -> startDownload(uri) }
            }
        }

        // Deteksi platform otomatis saat mengetik
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePlatformUi(s?.toString().orEmpty(), detectText)
            }
        })

        downloadButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                showToast("Tempel link video dulu")
                return@setOnClickListener
            }
            if (!isValidUrl(url)) {
                showToast("Link tidak valid")
                return@setOnClickListener
            }
            pendingFileName = fileNameInput.text.toString().trim()
                .ifEmpty { generateFileName() }
                .let { if (it.endsWith(".mp4", true)) it else "$it.mp4" }
            openFilePicker()
        }

        cancelButton.setOnClickListener {
            downloadJob?.cancel()
            showToast("Unduhan dibatalkan")
        }

        clearHistoryButton.setOnClickListener {
            DownloadHistory.clear(this)
            refreshHistory()
        }

        copyActionButton.setOnClickListener {
            pasteClipboardToInput()
        }
    }

    private fun showThemeDialog() {
        val labels = arrayOf(
            getString(R.string.theme_system),
            getString(R.string.theme_light),
            getString(R.string.theme_dark)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.theme_title)
            .setSingleChoiceItems(labels, ThemeHelper.getSaved(this)) { dialog, which ->
                ThemeHelper.save(this, which)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Platform yang didukung + label ramah untuk UI. */
    private fun detectPlatform(url: String): String? {
        val u = url.lowercase()
        return when {
            "youtube.com" in u || "youtu.be" in u -> "YouTube"
            "twitter.com" in u || "x.com" in u -> "Twitter/X"
            "instagram.com" in u -> "Instagram"
            "facebook.com" in u || "fb.watch" in u -> "Facebook"
            "tiktok.com" in u -> "TikTok"
            "reddit.com" in u || "redd.it" in u -> "Reddit"
            else -> null
        }
    }

    private fun updatePlatformUi(url: String, detectText: TextView) {
        if (url.isBlank()) {
            detectText.text = getString(R.string.detect_hint_idle)
            highlightChip(null)
            return
        }
        val platform = detectPlatform(url)
        if (platform != null) {
            detectText.text = getString(R.string.detect_found, platform)
            highlightChip(platform)
        } else if (isValidUrl(url)) {
            detectText.text = getString(R.string.detect_direct_try)
            highlightChip(null)
        } else {
            detectText.text = getString(R.string.detect_invalid)
            highlightChip(null)
        }
    }

    private fun highlightChip(platform: String?) {
        val map = mapOf(
            "YouTube" to R.id.chipYoutube,
            "Twitter/X" to R.id.chipTwitter,
            "Instagram" to R.id.chipInstagram,
            "Facebook" to R.id.chipFacebook,
            "TikTok" to R.id.chipTiktok,
            "Reddit" to R.id.chipReddit
        )
        map.forEach { (name, id) ->
            findViewById<Chip>(id)?.isChecked = (name == platform)
        }
    }

    private fun arePermissionsGranted(): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                checkPermissions(Manifest.permission.POST_NOTIFICATIONS)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Android 10+ pakai MediaStore/SAF — tanpa storage permission
                true
            }
            else -> {
                checkPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun checkPermissions(vararg permissions: String): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showPermissionRequestDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("This app needs permissions to access media files and show notifications.")
            .setPositiveButton("Grant") { _, _ -> requestPermissions() }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun requestPermissions() {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                // Tidak ada permission tambahan yang dibutuhkan
                showToast("Siap mengunduh")
                return
            }
            else -> {
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (::requestPermissionsLauncher.isInitialized) {
            requestPermissionsLauncher.launch(permissions)
        }
    }

    private fun pasteClipboardToInput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            showToast("Clipboard kosong")
            return
        }
        val pastedText = clip.getItemAt(0)?.text?.toString().orEmpty()
        val urls = extractUrls(pastedText)
        if (urls.isEmpty()) {
            showToast("Tidak ada link di clipboard")
            return
        }
        findViewById<TextInputEditText>(R.id.urlInput).setText(urls.first())
        showToast("Link ditempel: ${detectPlatform(urls.first()) ?: "langsung"}")
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/mp4"
            putExtra(Intent.EXTRA_TITLE, pendingFileName.ifEmpty { generateFileName() })
        }
        saveLocationLauncher.launch(intent)
    }

    private fun generateFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "video_${dateFormat.format(System.currentTimeMillis())}.mp4"
    }

    private fun startDownload(uri: Uri) {
        val url = findViewById<TextInputEditText>(R.id.urlInput).text.toString().trim()
        val platform = detectPlatform(url) ?: "Langsung"
        val fileName = pendingFileName.ifEmpty { generateFileName() }
        showProgressCard(true, fileName)
        lastProgressShown = -1

        downloadJob?.cancel()
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            // Tangkap Job sendiri (di dalam lambda `use` nanti tak tersedia)
            val myJob = coroutineContext[Job]
            try {
                updateProgressUi(0, "Mengambil link video…")
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.errorText)?.visibility = View.GONE
                }
                // Kumpulkan error tiap tahap agar user tahu persis yang gagal
                val stageErrors = mutableListOf<String>()
                fun <T> attempt(stage: String, block: () -> T?): T? {
                    return try {
                        block()
                    } catch (e: Exception) {
                        stageErrors.add("$stage: ${e.message?.take(150)}")
                        Log.d("Fetch", "$stage gagal: ${e.message}")
                        null
                    }
                }
                var videoUrl: String? = null
                when {
                    url.contains("youtube.com") || url.contains("youtu.be") ->
                        attempt("YouTube") { YouTubeUrlFetcher.fetchYouTubeVideoUrl(url) }
                    url.contains("twitter.com") || url.contains("x.com") ->
                        attempt("Twitter") { TwitterUrlFetcher.fetchTwitterVideoUrl(url) }
                    url.contains("instagram.com") ->
                        attempt("Instagram") { InstagramUrlFetcher.fetchInstagramVideoUrl(url) }
                    url.contains("facebook.com") || url.contains("fb.watch") ->
                        attempt("Facebook") { FacebookUrlFetcher.fetchFacebookVideoUrl(url) }
                    else ->
                        attempt("Direct") { DirectFileFetcher.fetchDirectMediaUrl(url) }
                }?.let { videoUrl = it }

                if (videoUrl == null) {
                    // Fallback terakhir: coba sebagai link file langsung
                    attempt("Direct") { DirectFileFetcher.fetchDirectMediaUrl(url) }
                        ?.let { videoUrl = it }
                }

                val finalUrl = videoUrl
                if (finalUrl == null) {
                    val reason = if (stageErrors.isEmpty()) "Link tidak dikenali"
                        else "Gagal retrieve — " + stageErrors.joinToString(" | ")
                    finishDownload(fileName, platform, false, reason)
                    return@launch
                }
                val success = downloadFile(finalUrl, uri, myJob)
                finishDownload(
                    fileName, platform, success,
                    if (success) "Video tersimpan" else "Unduhan gagal"
                )
            } catch (e: CancellationException) {
                cancelProgressNotification()
                withContext(Dispatchers.Main) {
                    showProgressCard(false)
                    showToast("Unduhan dibatalkan")
                    refreshHistory()
                }
            } catch (e: Exception) {
                Log.e("Download", "Error: ${e.message}", e)
                finishDownload(fileName, platform, false, "Error: ${e.message}")
            }
        }
    }

    private suspend fun finishDownload(fileName: String, platform: String, success: Boolean, message: String) {
        DownloadHistory.add(this, HistoryEntry(fileName, platform, DownloadHistory.now(), success))
        withContext(Dispatchers.Main) {
            showProgressCard(false)
            if (!success) {
                findViewById<TextView>(R.id.errorText)?.apply {
                    text = message
                    visibility = View.VISIBLE
                }
            }
            findViewById<TextInputEditText>(R.id.urlInput).text?.clear()
            findViewById<TextInputEditText>(R.id.fileNameInput).setText(generateFileName())
            showNotification(
                if (success) "Download Complete" else "Download Gagal",
                message
            )
            refreshHistory()
        }
    }

    private suspend fun downloadFile(videoUrl: String, uri: Uri, myJob: Job?): Boolean {
        // Reset download started flag at the beginning of each download
        isDownloadStarted = false

        return try {
            val request = Request.Builder().url(videoUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val totalBytes = response.body.contentLength() ?: 0L
                    var downloadedBytes = 0L

                    response.body.byteStream().use { input ->
                        contentResolver.openOutputStream(uri)?.use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                // Dukung tombol batal (coroutines 1.6: cek manual)
                                if (myJob?.isCancelled == true) {
                                    throw CancellationException()
                                }
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                // Calculate and show progress
                                val progress = if (totalBytes > 0) {
                                    (downloadedBytes * 100 / totalBytes).toInt()
                                } else {
                                    0
                                }

                                // Update notifikasi + UI dalam app (throttle per 2%)
                                if (progress != lastProgressShown && (progress % 2 == 0 || progress == 100)) {
                                    lastProgressShown = progress
                                    showProgressNotification(progress, totalBytes)
                                    updateProgressUi(progress, "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}")
                                }
                            }
                        }
                    }

                    // Remove progress notification and show completion notification
                    cancelProgressNotification()
                    showCompletionNotification("Download Complete", "Video saved successfully", true)
                    true
                } else {
                    // Remove progress notification and show error notification
                    cancelProgressNotification()
                    showCompletionNotification("Download Failed", "Unable to download the video", false)
                    false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Download", "Error: ${e.message}", e)
            // Remove progress notification and show error notification
            cancelProgressNotification()
            showCompletionNotification("Download Error", "Download failed: ${e.message}", false)
            false
        }
    }

    private fun showProgressCard(show: Boolean, fileName: String = "") {
        runOnUiThread {
            findViewById<View>(R.id.progressCard).visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                findViewById<TextView>(R.id.progressFileName).text = fileName
                findViewById<ProgressBar>(R.id.progressBar).progress = 0
                findViewById<TextView>(R.id.progressText).text = "Menyiapkan…"
            }
        }
    }

    private suspend fun updateProgressUi(progress: Int, detail: String) {
        withContext(Dispatchers.Main) {
            findViewById<ProgressBar>(R.id.progressBar)?.progress = progress.coerceIn(0, 100)
            findViewById<TextView>(R.id.progressText)?.text = "$progress% • $detail"
        }
    }

    private fun refreshHistory() {
        val container = findViewById<LinearLayout>(R.id.historyContainer) ?: return
        val emptyText = findViewById<TextView>(R.id.historyEmptyText)
        container.removeAllViews()
        val entries = DownloadHistory.list(this)
        emptyText?.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.clearHistoryButton)?.visibility =
            if (entries.isEmpty()) View.GONE else View.VISIBLE
        entries.take(8).forEach { e ->
            val row = TextView(this).apply {
                text = "${if (e.success) "✅" else "❌"} ${e.name} • ${e.platform} • ${e.date}"
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            container.addView(row)
        }
    }

    private fun showCompletionNotification(title: String, message: String, isSuccess: Boolean) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setAutoCancel(true)

        if (isSuccess) {
            builder.setSmallIcon(R.drawable.ic_file_download_done_24)
        } else {
            builder.setSmallIcon(R.drawable.ic_error_24)
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(this).notify(COMPLETION_NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            Log.e("Notification", "Error showing completion notification", e)
        }
    }

    private fun cancelProgressNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(PROGRESS_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e("Notification", "Error canceling progress notification", e)
        }
    }

    private fun showProgressNotification(progress: Int, totalBytes: Long) {
        // Ensure progress is within valid range
        val safeProgress = progress.coerceIn(0, 100)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Downloading Video")
            .setContentText("${formatFileSize(totalBytes)} - ${safeProgress}%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, safeProgress, false)

        // Only play sound and show heads-up notification on first progress update
        if (!isDownloadStarted) {
            builder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            isDownloadStarted = true
        } else {
            // Ensure silent updates for progress
            builder.setSound(null)
                .setSilent(true)
        }

        // Safely check and post notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, builder.build())
                }
            } else {
                NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            Log.e("ProgressNotification", "Error showing progress notification", e)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Download Notifications"
            val descriptionText = "Notifications for download status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String, isComplete: Boolean = false) {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI) // Play sound for completion/error

        if (isComplete) {
            builder.setOngoing(false)
                .setProgress(0, 0, false)
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Use a different notification ID for completion/error notifications
                NotificationManagerCompat.from(this).notify(PROGRESS_NOTIFICATION_ID + 1, builder.build())
            }
        } catch (e: Exception) {
            Log.e("Notification", "Error showing notification", e)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}