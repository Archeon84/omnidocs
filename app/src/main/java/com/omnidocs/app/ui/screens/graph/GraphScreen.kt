package com.omnidocs.app.ui.screens.graph

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val contradictions by viewModel.contradictions.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
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
        webView?.evaluateJavascript("loadGraph($json)", null)
    }

    LaunchedEffect(graphData) {
        graphData?.let { data -> pushGraphDataToWebView(data) }
    }

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
                title = { Text("Knowledge & Connections") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Visual Graph") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        if (contradictions.isEmpty()) viewModel.detectContradictions()
                    },
                    text = { Text("Contradictions (${contradictions.size})") }
                )
            }

            if (selectedTab == 0) {
                Box(modifier = Modifier.fillMaxSize()) {
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
                                    }
                                }

                                loadUrl("file:///android_asset/graph.html")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

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
            } else {
                // Contradiction view
                if (contradictions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No Contradictions Detected",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "All claims and decisions across your workspace are consistent.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(contradictions) { item ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNodeTap(item.noteIdA) }
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Conflicting Assertions",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "${(item.confidence * 100).toInt()}% CONFIDENCE",
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "• \"${item.claimA}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "• \"${item.claimB}\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = item.explanation,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
