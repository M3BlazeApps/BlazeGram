package mn.blazeapps.blazegram.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mn.blazeapps.blazegram.data.model.AuthState
import mn.blazeapps.blazegram.data.model.ChatType
import mn.blazeapps.blazegram.data.model.ItemUploadStatus
import mn.blazeapps.blazegram.ui.theme.*
import mn.blazeapps.blazegram.ui.viewmodel.TelegramViewModel
import mn.blazeapps.blazegram.ui.viewmodel.UploadViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: TelegramViewModel,
    uploadViewModel: UploadViewModel,
    onNavigateToSettings: () -> Unit
) {
    val authState by viewModel.authState.collectAsState()
    val chats by viewModel.chats.collectAsState()
    val isLoadingChats by viewModel.isLoadingChats.collectAsState()
    val selectedChatId by viewModel.selectedChatId.collectAsState()
    val folderSummary by uploadViewModel.folderSummary.collectAsState()

    var chatSearchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val selectedChat = chats.find { it.id == selectedChatId }

    val filteredChats = remember(chatSearchQuery, chats) {
        if (chatSearchQuery.isBlank()) {
            chats
        } else {
            chats.filter { it.title.contains(chatSearchQuery, ignoreCase = true) }
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            uploadViewModel.processSelectedFolder(uri)
        }
    }

    BackHandler(enabled = isSearchActive) {
        isSearchActive = false
        focusManager.clearFocus()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassTopAppBar(
                leadingBrandIcon = {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = mn.blazeapps.blazegram.R.drawable.ic_blazegram_logo),
                        contentDescription = "BlazeGram Logo",
                        modifier = Modifier
                            .size(44.dp)
                            .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp), spotColor = Color(0x666366F1))
                            .clip(RoundedCornerShape(14.dp))
                    )
                },
                title = {
                    Text(
                        text = "BlazeGram",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            fontSize = 20.sp
                        ),
                        color = Color.White
                    )
                },
                subtitle = {
                    Text(
                        text = "batch folder telegram uploader",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 0.2.sp
                        ),
                        color = ColorBlueVioletLight
                    )
                },
                actions = {
                    GlassIconButton(
                        onClick = onNavigateToSettings,
                        size = 36.dp,
                        shape = RoundedCornerShape(12.dp),
                        containerColor = GlassBg,
                        borderAlphaTop = 0.24f
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(17.dp),
                            tint = Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        GlassBackground(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                // If not logged in, prompt user to go to Settings
                if (authState !is AuthState.Ready) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0x2A351515),
                            borderAlphaTop = 0.45f,
                            borderAlphaBottom = 0.12f
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(Color(0x33FF453A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = ColorRedLight,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Not Logged in to Telegram",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Configure API ID, Hash & phone number in Settings to start uploading to your channels.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = onNavigateToSettings,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ColorBlueViolet,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("Open Settings", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    // Chat Search Bar
                    OutlinedTextField(
                        value = chatSearchQuery,
                        onValueChange = {
                            chatSearchQuery = it
                            isSearchActive = true
                        },
                        placeholder = {
                            Text(
                                text = if (isLoadingChats) {
                                    if (chats.isEmpty()) "Syncing chats..." else "Syncing chats (${chats.size} loaded)..."
                                } else if (chats.isNotEmpty()) {
                                    "Search ${chats.size} channels & chats…"
                                } else {
                                    "Search channels & chats…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = ColorBlueVioletLight,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (chatSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { chatSearchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = TextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Channel search dropdown overlay
                    if (isSearchActive) {
                        Spacer(modifier = Modifier.height(8.dp))
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Channels & Chats (${filteredChats.size})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary
                                )
                                TextButton(
                                    onClick = {
                                        isSearchActive = false
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }
                                ) {
                                    Text("Done", color = ColorBlueVioletLight, fontWeight = FontWeight.Bold)
                                }
                            }
                            HorizontalDivider(color = Color(0x14FFFFFF), thickness = 0.5.dp)

                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(filteredChats, key = { it.id }) { chat ->
                                    val isSelected = chat.id == selectedChatId
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.selectChat(chat.id)
                                                isSearchActive = false
                                                focusManager.clearFocus()
                                                keyboardController?.hide()
                                            }
                                            .background(if (isSelected) ColorBlueVioletDim else Color.Transparent)
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isSelected) Brush.linearGradient(listOf(ColorBlueViolet, ColorBlueVioletLight))
                                                    else Brush.linearGradient(listOf(Color(0x20FFFFFF), Color(0x10FFFFFF)))
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = when (chat.type) {
                                                    ChatType.CHANNEL -> Icons.Default.Campaign
                                                    ChatType.SUPERGROUP, ChatType.BASIC_GROUP -> Icons.Default.Groups
                                                    ChatType.PRIVATE -> Icons.Default.Person
                                                    else -> Icons.AutoMirrored.Filled.Chat
                                                },
                                                contentDescription = null,
                                                tint = if (isSelected) Color.White else TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chat.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) ColorBlueVioletSubtle else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = when (chat.type) {
                                                    ChatType.CHANNEL -> "Channel"
                                                    ChatType.SUPERGROUP -> "Supergroup"
                                                    ChatType.BASIC_GROUP -> "Group"
                                                    ChatType.PRIVATE -> "Private Chat"
                                                    ChatType.UNKNOWN -> "Chat"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextMuted
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = ColorBlueVioletLight,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = Color(0x0EFFFFFF), thickness = 0.5.dp)
                                }
                            }
                        }
                    }

                    // Active Channel Card
                    if (!isSearchActive && selectedChat != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isSearchActive = true },
                            backgroundColor = GlassBg,
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(ColorBlueViolet, ColorBlueVioletLight))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (selectedChat.type) {
                                            ChatType.CHANNEL -> Icons.Default.Campaign
                                            ChatType.SUPERGROUP, ChatType.BASIC_GROUP -> Icons.Default.Groups
                                            ChatType.PRIVATE -> Icons.Default.Person
                                            else -> Icons.AutoMirrored.Filled.Chat
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedChat.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Target Destination Channel",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ColorGreenLight
                                    )
                                }

                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ColorBlueVioletDim)
                                        .border(BorderStroke(1.dp, Color(0x666366F1)), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "Change",
                                        color = ColorBlueVioletSubtle,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Folder Selection Section
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { folderPickerLauncher.launch(null) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ColorOrange,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                enabled = folderSummary?.isUploading != true
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (folderSummary == null) "Select Folder to Upload" else "Change Folder",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Folder Staging & Batch Overview
                        if (folderSummary != null) {
                            val summary = folderSummary!!
                            Spacer(modifier = Modifier.height(12.dp))

                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = summary.folderName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${summary.totalCount} files · ${formatFileSize(summary.totalSizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = ColorOrangeLight,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    if (!summary.isUploading && !summary.isCompleted) {
                                        Button(
                                            onClick = { uploadViewModel.startUpload(selectedChat.id) },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = ColorGreen,
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Start Upload", fontWeight = FontWeight.Bold)
                                        }
                                    } else if (summary.isUploading) {
                                        PillBadge(
                                            text = "Uploading...",
                                            icon = Icons.Default.Sync,
                                            backgroundColor = ColorBlueVioletDim,
                                            borderColor = ColorBlueViolet,
                                            contentColor = ColorBlueVioletLight
                                        )
                                    } else if (summary.isCompleted) {
                                        PillBadge(
                                            text = "Completed",
                                            icon = Icons.Default.Check,
                                            backgroundColor = ColorGreenDim,
                                            borderColor = ColorGreen,
                                            contentColor = ColorGreenLight
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Batch Items List
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                ) {
                                    items(summary.items, key = { it.id }) { item ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (item.isVideo) ColorOrangeDim else ColorBlueVioletDim),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = if (item.isVideo) Icons.Default.Movie else Icons.Default.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = if (item.isVideo) ColorOrangeLight else ColorBlueVioletLight,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.originalFileName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${formatFileSize(item.size)} · ${item.statusMessage}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = when (item.status) {
                                                        ItemUploadStatus.COMPLETED -> ColorGreenLight
                                                        ItemUploadStatus.FAILED -> ColorRedLight
                                                        ItemUploadStatus.UPLOADING, ItemUploadStatus.CONVERTING_MP4 -> ColorOrangeLight
                                                        else -> TextMuted
                                                    }
                                                )
                                                if (item.progress > 0f && item.status != ItemUploadStatus.COMPLETED) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    LinearProgressIndicator(
                                                        progress = { item.progress },
                                                        modifier = Modifier.fillMaxWidth().height(3.dp),
                                                        color = ColorOrange,
                                                        trackColor = Color(0x20FFFFFF)
                                                    )
                                                }
                                            }

                                            if (item.status == ItemUploadStatus.COMPLETED) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = ColorGreen, modifier = Modifier.size(18.dp))
                                            } else if (item.status == ItemUploadStatus.FAILED) {
                                                Icon(Icons.Default.Error, contentDescription = null, tint = ColorRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                        HorizontalDivider(color = Color(0x0AFFFFFF), thickness = 0.5.dp)
                                    }
                                }

                                // Console Logs Box
                                if (summary.logs.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Activity Terminal",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 120.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xE605070E))
                                            .border(BorderStroke(0.5.dp, Color(0x1FFFFFFF)), RoundedCornerShape(10.dp))
                                            .padding(8.dp)
                                    ) {
                                        LazyColumn {
                                            items(summary.logs) { log ->
                                                Text(
                                                    text = "> $log",
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (log.contains("❌") || log.contains("⚠️")) ColorRedLight
                                                    else if (log.contains("✅") || log.contains("✨")) ColorGreenLight
                                                    else TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (!isSearchActive) {
                        // Empty state: prompt to select a channel first
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Campaign,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = "Select a Telegram Channel Above",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Search and select a channel or group to upload your files to.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
