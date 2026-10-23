package com.classmate.timetable.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.classmate.timetable.R
import com.classmate.timetable.data.Db
import com.classmate.timetable.data.Prefs
import com.classmate.timetable.databinding.ActivityJwWebBinding
import com.classmate.timetable.importer.ScheduleFileImporter
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject

/**
 * 内置浏览器登录教务系统，登录后一键抓取当前页面的课表表格。
 *
 * 抓取方式是「把页面里所有 table 连同 rowspan/colspan 一起取出来」，
 * 再交给和文件导入完全相同的网格解析器处理，所以不依赖某个学校的具体接口。
 */
class JwWebActivity : BaseActivity() {

    companion object {
        const val EXTRA_CAPTURE = "capture_payload"
        private const val SP_NAME = "jw_web"
        private const val KEY_URL = "last_url"
        private const val KEY_DESKTOP = "desktop_ua_mobile_default"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /** 默认用手机 UA：强智新版教务的手机页面更好操作，表格也更容易抓。 */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /** 常用入口：北京校区在前，保定校区单独列出，别混。 */
        private val PRESET_URLS = arrayOf(
            "http://202.204.70.211/jsxsd/",
            "https://my.ncepu.edu.cn",
            "http://202.204.74.177/sso/jziotlogin",
            "https://jw.ncepu.edu.cn/jiaowuchu"
        )
    }

    private lateinit var b: ActivityJwWebBinding
    private lateinit var db: Db
    private lateinit var prefs: Prefs
    private lateinit var sp: SharedPreferences

    private var desktopUa = false
    private var lastPageText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (prefs.dynamicColor) DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        b = ActivityJwWebBinding.inflate(layoutInflater)
        setContentView(b.root)

        db = Db.get(this)
        sp = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        b.toolbar.setNavigationOnClickListener { finish() }

        setupWebView()

        val saved = sp.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.jw_default_url)
        b.etUrl.setText(saved)
        b.btnOpen.setOnClickListener { openUrl() }
        b.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                openUrl()
                true
            } else {
                false
            }
        }
        b.btnCapture.setOnClickListener { capture() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.webView.canGoBack()) b.webView.goBack() else finish()
            }
        })

        if (savedInstanceState == null) openUrl()
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = b.webView.settings
        desktopUa = sp.getBoolean(KEY_DESKTOP, false)
        settings.userAgentString = if (desktopUa) DESKTOP_UA else MOBILE_UA

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportZoom(true)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.allowFileAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(b.webView, true)

        b.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // 非网页链接（如 tel:）交给系统
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.progress.visibility = View.VISIBLE
                b.tvHint.text = getString(R.string.jw_web_loading)
                b.etUrl.setText(url ?: b.etUrl.text)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.progress.visibility = View.GONE
                b.tvHint.text = getString(R.string.jw_web_loaded)
                CookieManager.getInstance().flush()
                if (!url.isNullOrBlank()) sp.edit().putString(KEY_URL, url).apply()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    b.progress.visibility = View.GONE
                    b.tvHint.text = getString(R.string.jw_web_failed, error?.description?.toString() ?: "")
                }
            }
        }

        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                b.progress.progress = newProgress
                if (newProgress >= 100) b.progress.visibility = View.GONE
            }
        }
    }

    private fun openUrl() {
        val input = b.etUrl.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) {
            toast(getString(R.string.jw_web_url_empty))
            return
        }
        val url = if (input.startsWith("http")) input else "https://$input"
        b.etUrl.setText(url)
        b.webView.loadUrl(url)
    }

    // ------------------------------------------------------------------ 抓取

    private fun capture() {
        if (b.webView.url.isNullOrBlank()) {
            toast(getString(R.string.jw_web_url_empty))
            return
        }
        b.btnCapture.isEnabled = false
        b.tvHint.text = getString(R.string.jw_web_capturing)

        b.webView.evaluateJavascript(CAPTURE_JS) { value ->
            b.btnCapture.isEnabled = true
            if (value.isNullOrBlank() || value == "null") {
                b.tvHint.text = getString(R.string.jw_web_no_table)
                return@evaluateJavascript
            }
            val payload = runCatching {
                when (val decoded = org.json.JSONTokener(value).nextValue()) {
                    is String -> decoded
                    is JSONObject -> decoded.toString()
                    else -> value
                }
            }.getOrElse { value }
            handlePayload(payload)
        }
    }

    private fun handlePayload(payload: String) {
        val result = runCatching {
            ScheduleFileImporter.fromCapture(payload, db.periods(), prefs.totalWeeks)
        }.getOrElse {
            ScheduleFileImporter.Result(message = getString(R.string.jw_capture_failed, it.message ?: "解析失败"))
        }

        if (result.courses.isEmpty()) {
            lastPageText = runCatching { JSONObject(payload).optString("text") }.getOrDefault("")
            b.tvHint.text = getString(R.string.jw_web_no_table)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.jw_web_title)
                .setMessage(result.message.ifBlank { getString(R.string.jw_web_no_table) })
                .setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.jw_web_copy_text) { _, _ -> copyPageText() }
                .show()
            return
        }

        setResult(RESULT_OK, Intent().putExtra(EXTRA_CAPTURE, payload))
        toast("已抓取 ${result.courses.size} 门课程")
        finish()
    }

    private fun copyPageText() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (lastPageText.isNotBlank()) {
            cm.setPrimaryClip(ClipData.newPlainText("page", lastPageText))
            toast(getString(R.string.jw_web_copied))
            return
        }
        b.webView.evaluateJavascript("(function(){try{return document.body?document.body.innerText:''}catch(e){return ''}})()") { value ->
            val text = runCatching {
                org.json.JSONTokener(value).nextValue() as? String
            }.getOrNull().orEmpty()
            if (text.isBlank()) {
                toast(getString(R.string.jw_web_no_table))
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("page", text))
                toast(getString(R.string.jw_web_copied))
            }
        }
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_jw_web, menu)
        menu.findItem(R.id.action_desktop)?.isChecked = desktopUa
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            b.webView.reload()
            true
        }
        R.id.action_presets -> {
            showPresets()
            true
        }
        R.id.action_desktop -> {
            desktopUa = !desktopUa
            item.isChecked = desktopUa
            sp.edit().putBoolean(KEY_DESKTOP, desktopUa).apply()
            b.webView.settings.userAgentString = if (desktopUa) DESKTOP_UA else MOBILE_UA
            b.webView.reload()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** 各校区 / 各版本教务入口，点一下直接打开。 */
    private fun showPresets() {
        val labels = resources.getStringArray(R.array.jw_presets)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.jw_presets_title)
            .setItems(labels) { _, which ->
                val url = PRESET_URLS.getOrElse(which) { return@setItems }
                b.etUrl.setText(url)
                b.webView.loadUrl(url)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroy() {
        runCatching {
            (b.root as android.view.ViewGroup).removeView(b.webView)
            b.webView.stopLoading()
            b.webView.destroy()
        }
        super.onDestroy()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private val CAPTURE_JS = """
        (function(){
          function txt(el){
            var t = el.innerText || el.textContent || '';
            return t.replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/^\s+|\s+$/g,'');
          }
          var tables = [];
          var tbs = document.querySelectorAll('table');
          for (var i = 0; i < tbs.length && i < 12; i++) {
            var trs = tbs[i].querySelectorAll('tr');
            if (trs.length < 3) continue;
            var rows = [];
            for (var r = 0; r < trs.length && r < 200; r++) {
              var tds = trs[r].querySelectorAll('td,th');
              var cells = [];
              for (var c = 0; c < tds.length && c < 30; c++) {
                var td = tds[c];
                var rs = parseInt(td.getAttribute('rowspan') || '1', 10) || 1;
                var cs = parseInt(td.getAttribute('colspan') || '1', 10) || 1;
                cells.push([txt(td), rs, cs]);
              }
              if (cells.length) rows.push(cells);
            }
            if (rows.length >= 3) tables.push(rows);
          }
          var text = '';
          try { text = (document.body ? document.body.innerText : '').slice(0, 20000); } catch (e) {}
          return JSON.stringify({ ok: true, url: location.href, count: tables.length, tables: tables, text: text });
        })()
    """.trimIndent()
}
