package com.lumina.tv.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lumina.tv.AppRuntime
import com.lumina.tv.data.*
import com.lumina.tv.ui.components.BrowseFocusCard
import com.lumina.tv.ui.components.LibraryPosterCard
import com.lumina.tv.ui.theme.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun loadBoxSets(client: EmbyClient, parentId: String? = null): List<MediaItem> =
    client.getItems(parentId = parentId, types = "BoxSet", limit = 40,
        fields = "PrimaryImageAspectRatio,ProductionYear").filter { it.type == "BoxSet" && it.id.isNotBlank() }

internal suspend fun loadCollectionPosters(client: EmbyClient, collectionId: String): List<MediaItem> =
    collectionPosterItems(client.getItems(parentId = collectionId, types = "Movie,Series,Video", limit = 4,
        fields = "PrimaryImageAspectRatio,ProductionYear"))

@Composable
internal fun MediaLibrary(
    views: List<LibraryView>, contents: Map<String, List<MediaItem>>, counts: Map<String, Int?>,
    gridState: LazyListState, active: Boolean, focusedLibraryId: String?, onLibraryFocused: (String) -> Unit,
    onOpenLibrary: (LibraryView) -> Unit, firstItemFocus: FocusRequester,
    onOpenDetail: (String) -> Unit, onOpenCollection: (MediaItem) -> Unit,
) {
    val client = AppRuntime.client
    val revision = LibraryCache.userDataRevision
    val collectionKey = "m09-boxsets:${client?.let { identityScope(it.baseUrl, it.userId) }}"
    var collections by remember(client, revision) { mutableStateOf(LibraryCache.browseOf(collectionKey).orEmpty()) }
    val posterRequests = remember { Semaphore(3) }
    LaunchedEffect(client, revision, active) {
        val c = client ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        val loaded = catchingRequest { loadBoxSets(c) }.getOrNull() ?: return@LaunchedEffect
        if (AppRuntime.client !== c || LibraryCache.userDataRevision != revision) return@LaunchedEffect
        collections = loaded
        LibraryCache.putBrowse(collectionKey, loaded, revision)
    }
    var pendingRestoreId by remember(gridState) { mutableStateOf(focusedLibraryId) }
    val restoreFocus = remember { FocusRequester() }
    LaunchedEffect(active, pendingRestoreId, collections.isNotEmpty(), views) {
        if (!active) return@LaunchedEffect
        val id = pendingRestoreId ?: return@LaunchedEffect
        val index = views.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        gridState.scrollToItem(index + 1 + if (collections.isNotEmpty()) 1 else 0)
        withFrameNanos { }
        runCatching { restoreFocus.requestFocus() }
        pendingRestoreId = null
    }
    LazyColumn(state = gridState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 88.dp, bottom = Space.section),
        verticalArrangement = Arrangement.spacedBy(Space.section)) {
        item(key = "library-title") {
            Text("媒体库", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.tvPagePadding())
        }
        // No heading or placeholder is emitted for an empty BoxSet response.
        if (collections.isNotEmpty()) item(key = "collections") {
            Column {
                Text("合集", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.tvPagePadding().padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp), modifier = Modifier.focusGroup()) {
                    items(collections, key = { it.id }) { collection ->
                        CollectionCard(collection, client, revision, posterRequests, active,
                            Modifier.width(200.dp).then(if (collection.id == collections.first().id) Modifier.focusRequester(firstItemFocus) else Modifier),
                            onClick = { onOpenCollection(collection) })
                    }
                }
            }
        }
        items(views, key = { "library-${it.id}" }) { view ->
            Column {
                BrowseFocusCard(Modifier.tvPagePadding()
                    .then(if (view.id == pendingRestoreId) Modifier.focusRequester(restoreFocus) else Modifier)
                    .then(if (collections.isEmpty() && view.id == views.first().id) Modifier.focusRequester(firstItemFocus) else Modifier),
                    onFocused = { onLibraryFocused(view.id) }, onClick = { onOpenLibrary(view) }) { focused ->
                    Column(Modifier.then(if (focused) Modifier.border(TvTokens.FocusBorder, Color.White, RoundedCornerShape(12.dp)) else Modifier)
                        .padding(6.dp)) {
                        Text(view.name.ifBlank { "媒体库" }, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                        Text(libraryCategoryCountLabel(counts[view.id], contents[view.id].orEmpty().size), color = TextSub, fontSize = 12.sp)
                    }
                }
                val posters = contents[view.id].orEmpty()
                if (posters.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 40.dp, vertical = 8.dp), modifier = Modifier.focusGroup()) {
                    items(posters, key = { it.id }) { item ->
                        LibraryPosterCard(item, client?.imageUrl(item, maxHeight = 480, maxWidth = 320), Modifier.width(124.dp),
                            onClick = { onOpenDetail(item.seriesId ?: item.id) })
                    }
                }
            }
        }
        if (views.isEmpty() && collections.isEmpty()) item(key = "library-empty") {
            BrowseFocusCard(Modifier.tvPagePadding().focusRequester(firstItemFocus), onClick = {}) { _ ->
                Text("服务器暂无媒体库内容", color = TextSub, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun CollectionCard(collection: MediaItem, client: EmbyClient?, revision: Long,
    requests: Semaphore, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cacheKey = "m09-collection:${client?.let { identityScope(it.baseUrl, it.userId) }}:${collection.id}"
    var posters by remember(client, revision, collection.id) { mutableStateOf(LibraryCache.browseOf(cacheKey).orEmpty()) }
    LaunchedEffect(client, revision, collection.id, active) {
        val c = client ?: return@LaunchedEffect
        if (!active || LibraryCache.browseOf(cacheKey) != null) return@LaunchedEffect
        val loaded = catchingRequest { requests.withPermit { loadCollectionPosters(c, collection.id) } }.getOrNull() ?: return@LaunchedEffect
        if (AppRuntime.client !== c || LibraryCache.userDataRevision != revision) return@LaunchedEffect
        posters = loaded
        LibraryCache.putBrowse(cacheKey, loaded, revision)
    }
    BrowseFocusCard(modifier, onClick = onClick) { focused ->
        Column {
            val shape = RoundedCornerShape(12.dp)
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).luminaSurface(SurfaceLevel.Content)
                .then(if (focused) Modifier.border(TvTokens.FocusBorder, Color.White, shape) else Modifier)) {
                val images = collectionPosterItems(posters).ifEmpty { listOf(collection) }
                if (images.size < 3) {
                    AsyncImage(client?.imageUrl(images.first(), maxHeight = 360, maxWidth = 480), null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    images.forEachIndexed { index, item ->
                        val offset = maxWidth * (index.toFloat() / (images.size - 1)) * 0.58f
                        AsyncImage(client?.imageUrl(item, maxHeight = 360, maxWidth = 240), null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.offset(x = offset, y = 8.dp).width(maxWidth * 0.42f).aspectRatio(2f / 3f)
                                .graphicsLayer { rotationZ = (index - (images.size - 1) / 2f) * 5f }
                                .clip(RoundedCornerShape(4.dp)))
                    }
                }
            }
            Text(readableMediaTitle(collection), color = if (focused) Color.White else Color(0xFF9AA3B2),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
