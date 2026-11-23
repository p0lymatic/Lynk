package com.p0lymatic.lynk.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.p0lymatic.lynk.data.Service
import com.p0lymatic.lynk.ssh.ConnectionManager
import com.p0lymatic.lynk.ui.theme.LynkColors
import com.p0lymatic.lynk.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The browser half of Lynk: every page is served from a local port that only exists while an
 * SSH forward is open. When the screen goes away, so does the forward.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    app: AppState,
    nav: Navigator,
    serverId: String,
    serviceId: String?,
    port: Int,
    scheme: String,
    path: String,
    title: String,
    host: String,
) {
    val context = LocalContext.current
    val server = app.store.server(serverId)

    var forward by remember { mutableStateOf<ConnectionManager.LocalForward?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var capturedIcon by remember { mutableStateOf<Bitmap?>(null) }
    var sslBypassed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var showSave by remember { mutableStateOf(false) }

    val target = "${if (host.isBlank()) "127.0.0.1" else host}:$port"

    // Open (or reuse) the SSH forward for the lifetime of this screen.
    LaunchedEffect(serverId, port, reloadKey) {
        val node = server ?: run {
            loadError = "Узел не найден"
            return@LaunchedEffect
        }
        loadError = null
        forward?.let { app.connections.closeForward(it) }
        forward = null
        val opened = withContext(Dispatchers.IO) {
            app.openService(
                Service(
                    id = serviceId ?: "adhoc-$port",
                    serverId = serverId,
                    name = title,
                    host = host.ifBlank { "127.0.0.1" },
                    port = port,
                    scheme = scheme,
                    path = path,
                ),
            )
        }
        if (opened == null) {
            loadError = app.connections.connState(serverId).error
                ?: "Не удалось открыть туннель к $target"
        }
        forward = opened
    }

    DisposableEffect(Unit) {
        onDispose {
            forward?.let { app.connections.closeForward(it) }
        }
    }

    val url = forward?.let { "${scheme.lowercase()}://127.0.0.1:${it.localPort}${normalizePath(path)}" }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else nav.pop()
    }

    Column(Modifier.fillMaxSize().background(LynkColors.bg)) {
        ScreenHeader(
            title = pageTitle.ifBlank { title.ifBlank { "Порт $port" } },
            subtitle = "${server?.name ?: "узел"} · $target",
            onBack = { nav.pop() },
            actions = {
                IconButton(onClick = { reloadKey++ }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Filled.Refresh, "Обновить", tint = LynkColors.textDim, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = {
                        url?.let {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                            }
                        }
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(Icons.Filled.Share, "Открыть в браузере", tint = LynkColors.textDim, modifier = Modifier.size(17.dp))
                }
                if (serviceId == null) {
                    IconButton(onClick = { showSave = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Star, "Сохранить сервис", tint = LynkColors.textDim, modifier = Modifier.size(18.dp))
                    }
                }
            },
        )

        Box(Modifier.fillMaxWidth().height(1.5.dp)) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxSize(),
                    color = LynkColors.textDim,
                    trackColor = Color.Transparent,
                )
            }
        }

        if (sslBypassed) {
            Text(
                text = "Сертификат сервиса не проверяется — Lynk подключён к нему по SSH",
                color = LynkColors.warn,
                fontSize = 10.5.sp,
                fontFamily = Mono,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(0xFF0F0F11.toInt())
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                builtInZoomControls = true
                                displayZoomControls = false
                                allowFileAccess = false
                                allowContentAccess = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                                userAgentString = "$userAgentString Lynk/0.1"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    loadError = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.title?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loadError = "Сервис не ответил на $target. Проверь, что он запущен на узле."
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: android.net.http.SslError?,
                                ) {
                                    sslBypassed = true
                                    handler?.proceed()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean = false
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }

                                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                                    if (icon != null) {
                                        capturedIcon = icon
                                        if (serviceId != null) {
                                            app.scope.launch(Dispatchers.IO) {
                                                saveIcon(app, serviceId, icon)
                                            }
                                        }
                                    }
                                }
                            }
                            loadUrl(url)
                            webView = this
                        }
                    },
                    update = { view ->
                        if (view.url != url) view.loadUrl(url)
                    },
                )
            }

            if (loadError != null) {
                Column(
                    modifier = Modifier.fillMaxSize().background(LynkColors.bg).padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Не открылось", fontSize = 16.sp, color = LynkColors.textDim)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        loadError!!,
                        fontSize = 12.5.sp,
                        color = LynkColors.textMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    GhostButton("Повторить", onClick = { reloadKey++ })
                }
            }
        }
    }

    if (showSave) {
        SaveServiceDialog(
            app = app,
            serverId = serverId,
            host = host.ifBlank { "127.0.0.1" },
            port = port,
            scheme = scheme,
            path = path,
            suggestedName = (pageTitle.ifBlank { title }).take(40),
            icon = capturedIcon,
            onDismiss = { showSave = false },
            onSaved = {
                showSave = false
                app.notify("Сервис сохранён")
            },
        )
    }
}

@Composable
private fun SaveServiceDialog(
    app: AppState,
    serverId: String,
    host: String,
    port: Int,
    scheme: String,
    path: String,
    suggestedName: String,
    icon: Bitmap?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LynkColors.surfaceHigh,
        titleContentColor = LynkColors.text,
        title = { Text("Сохранить сервис", fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "$host:$port · $scheme",
                    fontFamily = Mono,
                    fontSize = 11.5.sp,
                    color = LynkColors.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                LynkField(name, { name = it }, label = "Название", placeholder = "Панель")
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val id = UUID.randomUUID().toString()
                    app.store.upsertService(
                        Service(
                            id = id,
                            serverId = serverId,
                            name = name.ifBlank { "Порт $port" },
                            host = host,
                            port = port,
                            scheme = scheme,
                            path = normalizePath(path),
                            favorite = true,
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    if (icon != null) {
                        app.scope.launch(Dispatchers.IO) { saveIcon(app, id, icon) }
                    }
                    onSaved()
                },
            ) { Text("Сохранить", color = LynkColors.text) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Отмена", color = LynkColors.textDim)
            }
        },
    )
}

private fun saveIcon(app: AppState, serviceId: String, icon: Bitmap) {
    runCatching {
        val scaled = Bitmap.createScaledBitmap(icon, 96, 96, true)
        app.store.iconFile(serviceId).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        app.store.bumpIcon(serviceId)
    }
}

private fun normalizePath(path: String): String =
    if (path.isBlank()) "/" else if (path.startsWith("/")) path else "/$path"