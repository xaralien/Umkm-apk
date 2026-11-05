package id.kodesis.market

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // ===== Change this URL if your site ever moves =====
    private val startUrl = "https://market.kodesis.id/"

    // Dipakai otomatis bila situs belum punya sertifikat HTTPS (kamera & GPS di web
    // hanya jalan lewat HTTPS, jadi HTTPS dicoba lebih dulu).
    private val fallbackUrl = "http://market.kodesis.id/"
    private var usedFallback = false

    // Screenshot dan perekaman layar SELALU diizinkan di semua halaman
    // (tidak ada FLAG_SECURE, berbeda dengan aplikasi Kisa).

    // true  = tombol back/home/recent disembunyikan, menu web menempel di dasar layar
    //         (muncul sebentar saat digeser dari tepi bawah) - seperti game Arrows
    // false = tombol sistem selalu tampil, menu web duduk tepat di atasnya
    private val IMMERSIVE_NAV = true

    // Berapa lama tombol sistem dibiarkan tampil sebelum disembunyikan lagi (milidetik)
    private val NAV_AUTO_HIDE_MS = 5000L

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var splashOverlay: View
    private var splashHidden = false

    // State for <input type="file"> uploads
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    // Pending web permission callbacks waiting on the OS grant
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var webRtcRequest: PermissionRequest? = null

    // ---------- Activity result launchers ----------

    private val initialPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val geoPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            geoCallback?.invoke(geoOrigin, granted, false)
            geoCallback = null
            geoOrigin = null
        }

    private val webRtcPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val req = webRtcRequest
            if (req != null) {
                if (result.values.all { it }) req.grant(req.resources) else req.deny()
            }
            webRtcRequest = null
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleFileChooserResult(result)
        }

    // ---------- Lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Switch from the launch (splash) theme to the normal app theme
        setTheme(R.style.Theme_SapaUMKM)
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setContentView(R.layout.activity_main)

        setupSystemBars()

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        splashOverlay = findViewById(R.id.splashOverlay)

        // Safety net: hide the splash after 12s even if the page never reports "finished"
        splashOverlay.postDelayed({ hideSplash() }, 12000)

        requestInitialPermissions()
        configureWebView()

        swipeRefresh.setOnRefreshListener { webView.reload() }
        // Only allow pull-to-refresh when the page is scrolled to the very top
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    // ---------- Status bar / navigation bar spacing ----------

    /**
     * Sejak targetSdk 35+, Android memaksa tampilan edge-to-edge: konten digambar
     * sampai ke balik status bar dan tombol navigasi.
     *
     * Jarak bawah diambil dari tinggi navigation bar sistem yang sedang berlaku,
     * dan listener ini dipanggil ulang setiap kali bar itu muncul atau menghilang.
     * Hasilnya menu bawah halaman web mengikuti: naik ke atas tombol back/home saat
     * tombolnya tampil, lalu turun mengisi ruang itu saat tombolnya disembunyikan —
     * sama seperti perilaku Google Maps. Jarak atas mengikuti status bar, dan saat
     * keyboard terbuka jarak bawah menyesuaikan tinggi keyboard.
     */
    private fun setupSystemBars() {
        val root = findViewById<View>(android.R.id.content)
        root.setBackgroundColor(Color.WHITE)
        window.decorView.setBackgroundColor(Color.WHITE)

        // Android 15+ memasang scrim gelap sendiri di belakang tombol tiga-tombol saat
        // app menggambar edge-to-edge, menimpa warna apa pun yang kita set. Dua baris
        // ini mematikannya, sehingga latar putih di bawahnya yang terlihat.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // Android 14 ke bawah masih mematuhi warna bar secara langsung.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }

        val controller = WindowCompat.getInsetsController(window, window.decorView)

        // Latar putih -> ikon jam, sinyal, baterai, dan tombol navigasi dibuat gelap
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true

        if (IMMERSIVE_NAV) {
            // BEHAVIOR_DEFAULT (bukan TRANSIENT): saat digeser dari tepi bawah, tombol
            // sistem muncul sebagai bar sungguhan yang mengambil ruang layout, sehingga
            // menu web ikut terdorong naik ke atasnya.
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // Saat bar sedang beranimasi, biarkan callback animasi di bawah yang
            // mengatur jarak, supaya menu bergerak seiring tombol - bukan melompat.
            if (!barsAnimating) applyBarPadding(view, insets)

            if (IMMERSIVE_NAV) {
                val navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                scheduleHideNav(navVisible && !imeVisible)
            }

            WindowInsetsCompat.CONSUMED
        }

        // Menyamakan gerakan menu dengan animasi masuk/keluar bar sistem.
        ViewCompat.setWindowInsetsAnimationCallback(
            root,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {

                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    barsAnimating = true
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    applyBarPadding(root, insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    barsAnimating = false
                    ViewCompat.requestApplyInsets(root)
                }
            }
        )

        ViewCompat.requestApplyInsets(root)
    }

    private var barsAnimating = false

    /** Memberi jarak setinggi bar sistem (atau keyboard, mana yang lebih tinggi). */
    private fun applyBarPadding(view: View, insets: WindowInsetsCompat) {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
    }

    /** Menyembunyikan tombol sistem lagi beberapa detik setelah ia dimunculkan. */
    private fun scheduleHideNav(enable: Boolean) {
        val root = findViewById<View>(android.R.id.content)
        root.removeCallbacks(hideNavRunnable)
        if (enable) root.postDelayed(hideNavRunnable, NAV_AUTO_HIDE_MS)
    }

    private val hideNavRunnable = Runnable {
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.navigationBars())
    }

    // ---------- Permissions ----------

    private fun requestInitialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // No media/storage permission on Android 10+: uploads go through the system
        // file picker and DownloadManager can write to Downloads without permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Needed so DownloadManager's "download complete" notification can show
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val toRequest = perms.filter { !hasPermission(it) }
        if (toRequest.isNotEmpty()) {
            initialPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    // ---------- WebView setup ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setGeolocationEnabled(true)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.safeBrowsingEnabled = true
        s.userAgentString = s.userAgentString + " SapaUMKMApp/1.0"

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()

        // Any file the website triggers a download for (PDF, image, invoice, etc.)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    // ---------- Downloads ----------

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimetype: String?) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val cookie = CookieManager.getInstance().getCookie(url)

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                if (!cookie.isNullOrEmpty()) addRequestHeader("Cookie", cookie)
                addRequestHeader("User-Agent", userAgent ?: webView.settings.userAgentString)
                setMimeType(
                    mimetype?.takeIf { it.isNotBlank() }
                        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                            MimeTypeMap.getFileExtensionFromUrl(url)
                        ) ?: "application/octet-stream"
                )
                setDescription("Downloading $fileName")
                setTitle(fileName)
                // Shows progress while downloading and a notification when it's done
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createWebViewClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            // tel:, mailto:, whatsapp:, geo:, intent: ... hand off to the system
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "No app can open this link", Toast.LENGTH_SHORT).show()
                true
            }
        }

        // Situs tanpa HTTPS yang valid -> muat ulang lewat http:// (sekali saja)
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
            if (error?.url?.startsWith(startUrl) == true) loadFallback()
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true && request.url.toString().startsWith(startUrl)) loadFallback()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            swipeRefresh.isRefreshing = false
            hideSplash()
        }
    }

    private fun createWebChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        // GPS / navigator.geolocation
        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            if (hasLocationPermission()) {
                callback.invoke(origin, true, false)
            } else {
                geoOrigin = origin
                geoCallback = callback
                geoPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        // Camera / mic via getUserMedia (WebRTC, live camera stream)
        override fun onPermissionRequest(request: PermissionRequest) {
            val needed = mutableListOf<String>()
            request.resources.forEach { res ->
                when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        if (!hasPermission(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
                }
            }
            if (needed.isEmpty()) {
                request.grant(request.resources)
            } else {
                webRtcRequest = request
                webRtcPermissionLauncher.launch(needed.toTypedArray())
            }
        }

        // <input type="file"> uploads, with an option to take a photo
        override fun onShowFileChooser(
            webView: WebView?,
            callback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            openFileChooser(fileChooserParams)
            return true
        }
    }

    // ---------- File chooser + camera capture ----------

    private fun openFileChooser(params: WebChromeClient.FileChooserParams?) {
        val acceptTypes = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        val allowMultiple =
            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (acceptTypes.size == 1) acceptTypes[0] else "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            if (acceptTypes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
        }

        val cameraIntent = createCameraIntent()

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_TITLE, "Select a file or take a photo")
            if (cameraIntent != null) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
            }
        }

        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: Exception) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            Toast.makeText(this, "Cannot open the file picker", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCameraIntent(): Intent? {
        if (!hasPermission(Manifest.permission.CAMERA)) return null
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null
        val photoFile = try { createImageFile() } catch (e: Exception) { return null }
        cameraImageUri = FileProvider.getUriForFile(
            this, "${applicationContext.packageName}.fileprovider", photoFile
        )
        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        return intent
    }

    private fun createImageFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "camera").apply { if (!exists()) mkdirs() }
        return File(dir, "IMG_$stamp.jpg")
    }

    private fun handleFileChooserResult(result: ActivityResult) {
        val cb = filePathCallback ?: return
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            when {
                data?.dataString != null -> results = arrayOf(Uri.parse(data.dataString))
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    results = Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                cameraImageUri != null -> results = arrayOf(cameraImageUri!!)
            }
        }
        cb.onReceiveValue(results)
        filePathCallback = null
        cameraImageUri = null
    }

    // ---------- Helpers ----------

    private fun loadFallback() {
        if (usedFallback) return
        usedFallback = true
        webView.post { webView.loadUrl(fallbackUrl) }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(350)
            .withEndAction { splashOverlay.visibility = View.GONE }
            .start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Sembunyikan lagi tombol sistem setelah app kembali ke depan
        if (hasFocus && IMMERSIVE_NAV) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onPause() { super.onPause(); webView.onPause() }
    override fun onResume() { super.onResume(); webView.onResume() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
