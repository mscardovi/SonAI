package com.sonai.sonai.ui.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.unit.dp
import com.sonai.sonai.data.local.entity.FocusSession
import com.sonai.sonai.data.repository.SessionRepository
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(repository: SessionRepository, onBack: () -> Unit) {
    val sessions by repository.allSessions.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Text("<")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            items(sessions) { session ->
                SessionItem(session)
            }
        }
    }
}

@Composable
fun SessionItem(session: FocusSession) {
    val sdf = SimpleDateFormat("dd/MM HH:mm", LocalLocale.current.platformLocale)
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = sdf.format(Date(session.startTime)),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Focus Index: ${session.focusIndex}%",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Avg Noise: ${session.averageNoiseLevel.toInt()} dB",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
