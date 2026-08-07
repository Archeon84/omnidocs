package com.omnidocs.app.ui.screens.graph

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omnidocs.app.graph.GraphData
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    onNavigateBack: () -> Unit,
    onNodeTap: (String) -> Unit,
    navController: NavController,
    viewModel: GraphViewModel = hiltViewModel()
) {
    val graphData by viewModel.graphData.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    fun buildGraphJson(data: GraphData): String {
        return JSONObject().apply {
            put("nodes", JSONArray().apply {
                data.nodes.forEach { node ->
                    put(JSONObject().apply {
                        put("noteId", node.noteId)
                        put("title", node.title)
                        put("wordCount", node.wordCount)
                    })
                }
            })
            put("edges", JSONArray().apply {
                data.edges.forEach { edge ->
                    put(JSONObject().apply {
                        put("from", edge.from)
                        put("to", edge.to)
                        put("label", edge.label)
                        put("strength", edge.strength.toDouble())
                    })
                }
            })
        }.toString()
    }

    fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
        val argb = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        return "#%06X".format(argb and 0xFFFFFF)
    }

    fun pushGraphDataToWebView(data: GraphData) {
        val json = buildGraphJson(data)
        // Pass JSON directly to JS -- evaluateJavascript handles it safely
        webView?.evaluateJavascript("loadGraph($json)", null)
    }

    // Push graph data to WebView when it changes
    LaunchedEffect(graphData) {
        graphData?.let { data -> pushGraphDataToWebView(data) }
    }

    // Push theme colors
    LaunchedEffect(webView, colorScheme) {
        webView?.let { wv ->
            val themeJson = JSONObject().apply {
                put("primary", colorToHex(colorScheme.primary))
                put("surface", colorToHex(colorScheme.surface))
                put("onSurface", colorToHex(colorScheme.onSurface))
            }
            wv.evaluateJavascript("setGraphTheme($themeJson)", null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rebuildGraph() }) {
                        Icon(Icons.Default.Refresh, "Rebuild Graph")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onNodeTapped(noteId: String) {
                                onNodeTap(noteId)
                            }
                        }, "Android")

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Push data after page loads
                                graphData?.let { data -> pushGraphDataToWebView(data) }
                            }
                        }

                        loadUrl("file:///android_asset/graph.html")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Show error/empty state overlay
            val showOverlay = isLoading == false && (error != null || graphData?.edges?.isEmpty() == true)
            if (showOverlay) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = error ?: "No connections yet. Add more notes to see how they connect.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
