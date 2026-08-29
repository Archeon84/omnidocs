package com.omnidocs.app.ui.screens.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.omnidocs.app.data.local.entity.ClaimEntity

/**
 * Panel showing evidence extracted from a note by AI.
 * Displays claims grouped by type with confidence badges and approval controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidencePanel(
    claims: List<ClaimEntity>,
    onApprove: (ClaimEntity) -> Unit,
    onReject: (ClaimEntity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val groupedClaims = claims.groupBy { it.claimType }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Evidence Extracted",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (claims.isEmpty()) {
                Text(
                    text = "No evidence extracted yet. Save the note to run extraction.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Summary badges
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val approved = claims.count { it.status == "user_approved" }
                    val pending = claims.count { it.status == "ai_suggested" }
                    if (approved > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("$approved approved") },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) }
                        )
                    }
                    if (pending > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text("$pending pending") },
                            leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(16.dp)) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Claims grouped by type
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedClaims.forEach { (type, typeClaims) ->
                        item {
                            Text(
                                text = type.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        items(typeClaims, key = { it.id }) { claim ->
                            ClaimCard(
                                claim = claim,
                                onApprove = { onApprove(claim) },
                                onReject = { onReject(claim) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ClaimCard(
    claim: ClaimEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when (claim.status) {
        "user_approved" -> MaterialTheme.colorScheme.primary
        "user_rejected" -> MaterialTheme.colorScheme.error
        "uncertain" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }

    val statusLabel = when (claim.status) {
        "user_approved" -> "Approved"
        "user_rejected" -> "Rejected"
        "uncertain" -> "Uncertain"
        "ai_suggested" -> "Needs review"
        else -> claim.status
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Confidence and status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Confidence badge
                val confidencePercent = (claim.confidence * 100).toInt()
                AssistChip(
                    onClick = {},
                    label = { Text("$confidencePercent% confidence") },
                    leadingIcon = {
                        if (claim.confidence < 0.5f) {
                            Icon(Icons.Default.Warning, null, Modifier.size(14.dp))
                        }
                    }
                )

                // Status badge
                SuggestionChip(
                    onClick = {},
                    label = { Text(statusLabel, style = MaterialTheme.typography.labelSmall) },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = statusColor.copy(alpha = 0.1f),
                        labelColor = statusColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Claim text
            Text(
                text = claim.text,
                style = MaterialTheme.typography.bodyMedium
            )

            // Action buttons for pending claims
            if (claim.status == "ai_suggested") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onApprove,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject")
                    }
                }
            }
        }
    }
}
