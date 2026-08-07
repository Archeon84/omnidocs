package com.omnidocs.app.ui.screens.graph

import android.annotation.SuppressLint
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omnidocs.app.ui.navigation.Screen
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
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Push graph data to WebView when it changes
    LaunchedEffect(graphData) {
        graphData?.let { data ->
            val json = JSONObject().apply {
                put("nodes", org.json.JSONArray().apply {
                    data.nodes.forEach { node ->
                        put(org.json.JSONObject().apply {
                            put("id", node.noteId)
                            put("title", node.title)
                            put("wordCount", node.wordCount)
                        })
                    }
                })
                put("edges", org.json.JSONArray().apply {
                    data.edges.forEach { edge ->
                        put(org.json.JSONObject().apply {
                            put("source", edge.from)
                            put("target", edge.to)
                            put("label", edge.label)
                            put("strength", edge.strength)
                        })
                    }
                })
            }
            webView?.evaluateJavascript("loadGraph('${json.toString().replace("'", "\\'")}')", null)
        }
    }

    // Push theme colors
    LaunchedEffect(webView, colorScheme) {
        webView?.let { wv ->
            val themeJson = JSONObject().apply {
                put("primary", "#%06X".format(colorScheme.primary.hashCode() and 0xFFFFFF))
                put("surface", "#%06X".format(colorScheme.surface.hashCode() and 0xFFFFFF))
                put("onSurface", "#%06X".format(colorScheme.onSurface.hashCode() and 0xFFFFFF))
                put("primaryContainer", "#%06X".format(colorScheme.primaryContainer.hashCode() and 0xFFFFFF))
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
                                graphData?.let { data ->
                                    val json = JSONObject().apply {
                                        put("nodes", org.json.JSONArray().apply {
                                            data.nodes.forEach { node ->
                                                put(org.json.JSONObject().apply {
                                                    put("id", node.noteId)
                                                    put("title", node.title)
                                                    put("wordCount", node.wordCount)
                                                })
                                            }
                                        })
                                        put("edges", org.json.JSONArray().apply {
                                            data.edges.forEach { edge ->
                                                put(org.json.JSONObject().apply {
                                                    put("source", edge.from)
                                                    put("target", edge.to)
                                                    put("label", edge.label)
                                                    put("strength", edge.strength)
                                                })
                                            }
                                        })
                                    }
                                    view?.evaluateJavascript("loadGraph('${json.toString().replace("'", "\\'")}')", null)
                                }
                            }
                        }

                        loadUrl("file:///android_asset/graph.html")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            error?.let { msg ->
                if (graphData?.edges?.isEmpty() == true) {
                    // Show empty state overlay
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}