package com.auramusic.app.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONTokener
import androidx.compose.runtime.rememberCoroutineScope

private const val DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Scrolls the playlist page and collects every row it sees (Spotify web player, Amazon Music web).
private const val SCRAPE_JS = """
(function(){
  window.__rows = {}; window.__done = false; window.__n = 0;
  function grab(){
    document.querySelectorAll('[data-testid="tracklist-row"]').forEach(function(r){
      var t = r.querySelector('a[href*="/track/"]'); if(!t) return;
      var a = [].map.call(r.querySelectorAll('a[href*="/artist/"]'), function(x){return x.textContent.trim();}).join(', ');
      window.__rows[t.textContent.trim() + '\u0001' + a] = 1;
    });
    document.querySelectorAll('music-image-row,music-text-row,music-list-item').forEach(function(r){
      var p = r.getAttribute('primary-text'), s = r.getAttribute('secondary-text') || '';
      if(p) window.__rows[p + '\u0001' + s] = 1;
    });
    window.__n = Object.keys(window.__rows).length;
  }
  function scroller(){
    var row = document.querySelector('[data-testid="tracklist-row"],music-image-row,music-text-row,music-list-item');
    var e = row;
    while(e && e !== document.body){
      var o = getComputedStyle(e).overflowY;
      if((o === 'auto' || o === 'scroll') && e.scrollHeight > e.clientHeight + 20) return e;
      e = e.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }
  var still = 0, ticks = 0, last = -1;
  var timer = setInterval(function(){
    grab();
    var s = scroller();
    s.scrollTop = s.scrollTop + Math.max(200, s.clientHeight * 0.7);
    var pos = Math.round(s.scrollTop);
    if(pos === last) still++; else still = 0;
    last = pos; ticks++;
    if(still >= 5 || ticks > 600){ clearInterval(timer); grab(); window.__done = true; }
  }, 350);
})();
"""

/** Full-screen web view: the user logs in if needed, then taps "Read playlist". */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlaylistImportDialog(
    url: String,
    onResult: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("Log in if asked, wait for the playlist to appear, then tap Read playlist.") }
    var reading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!reading) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(enabled = !reading, onClick = onDismiss) { Text("Close") }
                    TextButton(
                        enabled = !reading && webView != null,
                        onClick = {
                            val wv = webView ?: return@TextButton
                            reading = true
                            scope.launch {
                                wv.evaluateJavascript(SCRAPE_JS, null)
                                var done = false
                                var guard = 0
                                while (!done && guard < 1500) {
                                    delay(500)
                                    guard++
                                    val r = kotlinx.coroutines.suspendCancellableCoroutine<String> { c ->
                                        wv.evaluateJavascript("window.__n + ',' + window.__done") { c.resume(it.trim('"')) }
                                    }
                                    status = "Reading... ${r.substringBefore(',')} titles"
                                    done = r.endsWith("true")
                                }
                                val raw = kotlinx.coroutines.suspendCancellableCoroutine<String> { c ->
                                    wv.evaluateJavascript("JSON.stringify(Object.keys(window.__rows))") { c.resume(it) }
                                }
                                val rows = runCatching {
                                    val inner = JSONTokener(raw).nextValue() as String
                                    val arr = JSONArray(inner)
                                    (0 until arr.length()).map { arr.getString(it) }
                                }.getOrDefault(emptyList())
                                reading = false
                                if (rows.isEmpty()) status = "No titles found. Open the playlist page, then try again."
                                else onResult(rows)
                            }
                        },
                    ) { Text(if (reading) "Reading..." else "Read playlist") }
                }
                Text(status, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = DESKTOP_UA
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(url)
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
