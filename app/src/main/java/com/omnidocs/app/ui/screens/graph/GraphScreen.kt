package com.omnidocs.app.ui.screens.graph

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.omnidocs.app.graph.GraphData
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DegreeFilter(val label: String, val minDegree: Int) {
    ALL("All Notes", 0),
    CONNECTED("Connected (1+)", 1),
    HUBS("Hubs (3+)", 3)
}

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
    val evolutionTimeline by viewModel.evolutionTimeline.collectAsState()
    val selectedNotePreview by viewModel.selectedNotePreview.collectAsState()
    val allTags by viewModel.allTags.collectAsState()
    val isAiScanning by viewModel.isAiScanning.collectAsState()
    val aiScanProgress by viewModel.aiScanProgress.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var showExportMenu by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    // Visual Graph Search & Filter
    var searchQuery by remember { mutableStateOf("") }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }
    var degreeFilter by remember { mutableStateOf(DegreeFilter.ALL) }

    // Idea Evolution Concept Input
    var evolutionConceptInput by remember { mutableStateOf("") }

    fun buildGraphJson(data: GraphData): String {
        return JSONObject().apply {
            put("nodes", JSONArray().apply {
                data.nodes.forEach { node ->
                    put(JSONObject().apply {
                        put("noteId", node.noteId)
                        put("title", node.title)
                        put("wordCount", node.wordCount)
                        put("tags", JSONArray(node.tags))
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

    var pendingGraphData by remember { mutableStateOf<GraphData?>(null) }

    LaunchedEffect(webView, colorScheme) {
        webView?.let { wv ->
            val themeJson = JSONObject().apply {
                put("primary", colorToHex(colorScheme.primary))
                put("surface", colorToHex(colorScheme.surface))
                put("onSurface", colorToHex(colorScheme.onSurface))
            }
            wv.evaluateJavascript("setGraphTheme($themeJson)", null)
            pendingGraphData?.let { pushGraphDataToWebView(it) }
        }
    }

    LaunchedEffect(graphData) {
        val data = graphData
        if (data != null) {
            pendingGraphData = data
            if (webView != null) {
                pushGraphDataToWebView(data)
            }
        }
    }

    // Reactively update search, tag filter, and degree centrality in WebView canvas
    LaunchedEffect(searchQuery, selectedTagFilter, degreeFilter, webView) {
        val q = searchQuery.replace("'", "\\'")
        val t = (selectedTagFilter ?: "").replace("'", "\\'")
        val deg = degreeFilter.minDegree
        webView?.evaluateJavascript("filterGraph('$q', '$t', $deg)", null)
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
                    IconButton(
                        onClick = { viewModel.enrichWithAi() },
                        enabled = !isAiScanning && (graphData?.nodes?.isNotEmpty() == true)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Deep Scan",
                            tint = if (isAiScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.rebuildGraph() }) {
                        Icon(Icons.Default.Refresh, "Rebuild Graph")
                    }
                    Box {
                        IconButton(onClick = { showExportMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Export Graph")
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export GraphML (.graphml)") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportGraphml(context)
                                },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Export JSON-LD (.jsonld)") },
                                onClick = {
                                    showExportMenu = false
                                    viewModel.exportJsonLd(context)
                                },
                                leadingIcon = { Icon(Icons.Default.Code, null) }
                            )
                        }
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
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Idea Evolution") }
                )
            }

            when (selectedTab) {
                0 -> {
                    // ── TAB 0: Visual Knowledge Graph with Interactive Tools ─────────
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Graph Search & Filter Controls
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Search nodes in graph...", fontSize = 13.sp) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(24.dp)
                                )

                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    DegreeFilter.entries.forEach { df ->
                                        FilterChip(
                                            selected = degreeFilter == df,
                                            onClick = { degreeFilter = df },
                                            leadingIcon = if (df == DegreeFilter.HUBS) {
                                                { Icon(Icons.Default.Stars, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                            } else if (df == DegreeFilter.CONNECTED) {
                                                { Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                            } else null,
                                            label = { Text(df.label, fontSize = 12.sp) }
                                        )
                                    }
                                    allTags.forEach { tag ->
                                        FilterChip(
                                            selected = selectedTagFilter == tag,
                                            onClick = {
                                                selectedTagFilter = if (selectedTagFilter == tag) null else tag
                                            },
                                            label = { Text("#$tag", fontSize = 12.sp) }
                                        )
                                    }
                                }

                                if (isAiScanning) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = aiScanProgress ?: "AI scanning connections in background...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }

                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        webView = this
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true

                                        addJavascriptInterface(object {
                                            @JavascriptInterface
                                            fun onNodeTapped(noteId: String) {
                                                viewModel.selectNodeForPreview(noteId)
                                                post {
                                                    evaluateJavascript("selectNode('$noteId')", null)
                                                }
                                            }
                                        }, "Android")

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                super.onPageFinished(view, url)
                                                pendingGraphData?.let { pushGraphDataToWebView(it) }
                                            }
                                        }

                                        loadUrl("file:///android_asset/graph.html")
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            val showOverlay = !isLoading && (error != null && graphData?.nodes?.isEmpty() == true)
                            if (showOverlay) {
                                Surface(
                                    modifier = Modifier.align(Alignment.Center),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                                ) {
                                    Text(
                                        text = error ?: "No notes found in workspace.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.padding(24.dp),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // ── TAB 1: Contradictions Detection ──────────────────────────────
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (contradictions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
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
                                    text = "All verified claims and decisions across your workspace notes are consistent.",
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

                2 -> {
                    // ── TAB 2: Idea & Concept Evolution Timeline ─────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = evolutionConceptInput,
                                onValueChange = { evolutionConceptInput = it },
                                placeholder = { Text("Trace concept (e.g. SQLite, Revenue, Architecture)...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = { viewModel.traceEvolution(evolutionConceptInput) },
                                enabled = evolutionConceptInput.isNotBlank() && !isLoading
                            ) {
                                Icon(Icons.Default.Timeline, "Trace")
                            }
                        }

                        if (allTags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Quick Topics:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                                allTags.take(8).forEach { tag ->
                                    SuggestionChip(
                                        onClick = {
                                            evolutionConceptInput = tag
                                            viewModel.traceEvolution(tag)
                                        },
                                        label = { Text("#$tag", fontSize = 11.sp) }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (evolutionTimeline != null) {
                            val timeline = evolutionTimeline!!
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item {
                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                    ) {
                                        Column(Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = timeline.concept,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Surface(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = timeline.currentStatus,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            timeline.firstMention?.let { first ->
                                                Spacer(Modifier.height(8.dp))
                                                val dateStr = remember(first.createdAt) {
                                                    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(first.createdAt))
                                                }
                                                Text(
                                                    text = "First introduced in \"${first.title}\" on $dateStr",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }

                                if (timeline.decisions.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Key Decisions (${timeline.decisions.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    items(timeline.decisions) { decision ->
                                        Card(
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    text = decision.text,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }

                                if (timeline.relatedNotes.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Chronological Note History (${timeline.relatedNotes.size})",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    items(timeline.relatedNotes) { note ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            // Timeline dot and vertical indicator
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.padding(end = 12.dp, top = 4.dp)
                                            ) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(12.dp)
                                                ) {}
                                            }

                                            Card(
                                                shape = RoundedCornerShape(10.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { onNodeTap(note.id) }
                                            ) {
                                                Column(Modifier.padding(12.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = note.title.ifBlank { "Untitled" },
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                        val dateStr = remember(note.createdAt) {
                                                            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(note.createdAt))
                                                        }
                                                        Text(
                                                            text = dateStr,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                    Spacer(Modifier.height(4.dp))
                                                    Text(
                                                        text = note.plainText.take(140).trim() + if (note.plainText.length > 140) "..." else "",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.AccountTree,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = "Trace how any concept evolved over time across your notes",
                                        style = MaterialTheme.typography.bodyMedium,
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

    // ── Interactive Node Preview Modal Bottom Sheet ───────────────────────────
    if (selectedNotePreview != null) {
        val note = selectedNotePreview!!
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.selectNodeForPreview(null)
                webView?.evaluateJavascript("selectNode(null)", null)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = note.title.ifBlank { "Untitled Note" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val noteId = note.id
                        viewModel.selectNodeForPreview(null)
                        webView?.evaluateJavascript("selectNode('$noteId')", null)
                    }) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Focus", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Action row: Open Note, Add Linked Note, Ask AI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            viewModel.selectNodeForPreview(null)
                            onNodeTap(note.id)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }

                    OutlinedButton(
                        onClick = {
                            val targetTitle = note.title.ifBlank { "Untitled" }
                            viewModel.selectNodeForPreview(null)
                            navController.navigate(com.omnidocs.app.ui.navigation.Screen.Editor.createRoute())
                            navController.currentBackStackEntry?.savedStateHandle?.set("ocrText", "<p>Linked to [[$targetTitle]]</p><p></p>")
                        },
                        modifier = Modifier.weight(1.3f)
                    ) {
                        Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Link Note")
                    }

                    FilledTonalButton(
                        onClick = {
                            viewModel.selectNodeForPreview(null)
                            navController.navigate(com.omnidocs.app.ui.navigation.Screen.Ask.route)
                        }
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "Ask AI", modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Tag Chips
                val tags = remember(note.tags) {
                    try {
                        val arr = JSONArray(note.tags)
                        (0 until arr.length()).map { arr.getString(it) }
                    } catch (_: Exception) { emptyList() }
                }
                if (tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag ->
                            SuggestionChip(
                                onClick = {
                                    selectedTagFilter = tag
                                    viewModel.selectNodeForPreview(null)
                                },
                                label = { Text("#$tag", fontSize = 12.sp) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Plain text snippet
                Text(
                    text = note.plainText.take(300).trim() + if (note.plainText.length > 300) "..." else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                // Connected Neighbors
                val neighborEdges = remember(graphData, note.id) {
                    graphData?.edges?.filter { it.from == note.id || it.to == note.id } ?: emptyList()
                }
                if (neighborEdges.isNotEmpty()) {
                    Text(
                        text = "Connected Notes (${neighborEdges.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(neighborEdges) { edge ->
                            val neighborId = if (edge.from == note.id) edge.to else edge.from
                            val neighborTitle = graphData?.nodes?.find { it.noteId == neighborId }?.title ?: "Note"
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectNodeForPreview(neighborId)
                                        webView?.evaluateJavascript("selectNode('$neighborId')", null)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = neighborTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = edge.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
