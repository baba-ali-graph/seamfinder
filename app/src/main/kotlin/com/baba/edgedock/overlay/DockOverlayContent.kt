package com.baba.edgedock.overlay

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.baba.edgedock.EdgeDockApp
import com.baba.edgedock.R
import com.baba.edgedock.data.AppEntry
import com.baba.edgedock.data.AppRepository
import com.baba.edgedock.data.AppsLayout
import com.baba.edgedock.data.UserPreferences
import com.baba.edgedock.util.formatLastUsed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BubbleSize = 56.dp
private val PanelWidth = 300.dp
private val PanelHeight = 420.dp
private val PanelCorner = 24.dp
private const val ItemStaggerMs = 35L

@Composable
fun DockOverlayContent(
    expanded: Boolean,
    morphProgress: () -> Float,
    panelSettled: Boolean,
    bubbleX: Int,
    bubbleY: Int,
    onExpandedChange: (Boolean) -> Unit,
    onBubbleDrag: (dx: Float, dy: Float) -> Unit,
    onBubbleDragEnd: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember {
        (context.applicationContext as EdgeDockApp).appRepository
    }
    val layoutDirection = LocalLayoutDirection.current

    // Absolute left/top so a RTL locale cannot anchor the morph on the right edge.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (expanded) {
                SearchPanel(
                    repository = repository,
                    progress = morphProgress,
                    settled = panelSettled,
                    bubbleX = bubbleX,
                    bubbleY = bubbleY,
                    contentDirection = layoutDirection,
                    onDismiss = { onExpandedChange(false) },
                )
            } else {
                Box(modifier = Modifier.offset { IntOffset(bubbleX, bubbleY) }) {
                    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                        Bubble(
                            onTap = { onExpandedChange(true) },
                            onDrag = onBubbleDrag,
                            onDragEnd = onBubbleDragEnd,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(
    onTap: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bubbleScale",
    )
    // detectDragGestures only runs after touch-slop, so pure taps never fire.
    // Handle tap vs drag from the initial down ourselves.
    val touchSlop = LocalViewConfiguration.current.touchSlop

    Box(
        modifier = Modifier
            .size(BubbleSize)
            .scale(scale)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.0f),
                    ),
                    radius = 90f,
                ),
                shape = CircleShape,
            )
            .padding(6.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f), CircleShape)
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var lastPosition = down.position

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: run {
                            if (dragging) onDragEnd() else onTap()
                            break
                        }

                        if (change.pressed) {
                            val dragAmount = change.position - lastPosition
                            lastPosition = change.position
                            val distanceFromStart = (change.position - down.position).getDistance()
                            if (!dragging && distanceFromStart > touchSlop) {
                                dragging = true
                            }
                            if (dragging) {
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        } else {
                            change.consume()
                            if (dragging) onDragEnd() else onTap()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = stringResource(R.string.cd_open_search),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SearchPanel(
    repository: AppRepository,
    progress: () -> Float,
    settled: Boolean,
    bubbleX: Int,
    bubbleY: Int,
    contentDirection: LayoutDirection,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { UserPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val appsLayout by preferences.appsLayout.collectAsStateWithLifecycle(initialValue = AppsLayout.LIST)

    var query by remember { mutableStateOf("") }
    var recentApps by remember { mutableStateOf(repository.cachedRecentApps()) }
    var allApps by remember { mutableStateOf(repository.cachedAllApps()) }
    var usageAccessGranted by remember { mutableStateOf(repository.hasUsageAccess()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(settled) {
        if (!settled) return@LaunchedEffect
        launch { recentApps = repository.recentApps(limit = 5) }
        launch { allApps = repository.allLaunchableApps() }
        usageAccessGranted = repository.hasUsageAccess()
    }

    val filteredApps by remember {
        derivedStateOf {
            if (query.isBlank()) recentApps else allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
    }

    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val bubbleColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
    val panelColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
    val iconTint = MaterialTheme.colorScheme.onPrimaryContainer

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val bubblePx = with(density) { BubbleSize.toPx() }
        val panelW = with(density) { PanelWidth.toPx() }.coerceAtMost(screenW - with(density) { 24.dp.toPx() })
        val panelH = with(density) { PanelHeight.toPx() }.coerceAtMost(screenH - with(density) { 48.dp.toPx() })
        val cornerPx = with(density) { PanelCorner.toPx() }
        val layoutLeft = (screenW - panelW) / 2f
        val layoutTop = (screenH - panelH) / 2f
        val iconPx = with(density) { 24.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val t = progress().coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = 0.40f * t))
                }
                .then(
                    if (settled) {
                        Modifier.pointerInput(onDismiss) {
                            detectTapGestures(onTap = { onDismiss() })
                        }
                    } else {
                        Modifier
                    },
                ),
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(layoutLeft.roundToInt(), layoutTop.roundToInt()) }
                .size(with(density) { panelW.toDp() }, with(density) { panelH.toDp() })
                .graphicsLayer {
                    val t = progress().coerceIn(0f, 1f)
                    scaleX = lerp(bubblePx / panelW, 1f, t)
                    scaleY = lerp(bubblePx / panelH, 1f, t)
                    translationX = lerp(bubbleX + bubblePx / 2f, screenW / 2f, t) - screenW / 2f
                    translationY = lerp(bubbleY + bubblePx / 2f, screenH / 2f, t) - screenH / 2f
                    transformOrigin = TransformOrigin.Center
                    clip = true
                    shape = MorphClipShape(
                        radiusX = lerp(panelW / 2f, cornerPx, t),
                        radiusY = lerp(panelH / 2f, cornerPx, t),
                    )
                }
                .drawBehind {
                    drawRect(androidx.compose.ui.graphics.lerp(bubbleColor, panelColor, progress().coerceIn(0f, 1f)))
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            if (settled) {
            CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { keyboardController?.hide() },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (query.isBlank()) {
                                stringResource(R.string.section_recent)
                            } else {
                                stringResource(R.string.section_results, filteredApps.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = onSurfaceVariant,
                        )
                        IconButton(
                            onClick = {
                                val next = if (appsLayout == AppsLayout.LIST) AppsLayout.GRID else AppsLayout.LIST
                                scope.launch { preferences.setAppsLayout(next) }
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = if (appsLayout == AppsLayout.LIST) {
                                    Icons.Default.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = if (appsLayout == AppsLayout.LIST) {
                                    stringResource(R.string.cd_switch_to_grid)
                                } else {
                                    stringResource(R.string.cd_switch_to_list)
                                },
                                tint = onSurfaceVariant,
                            )
                        }
                    }
                    if (filteredApps.isEmpty()) {
                        Text(
                            text = when {
                                query.isNotBlank() -> stringResource(R.string.empty_no_results)
                                !usageAccessGranted -> stringResource(R.string.empty_usage_access)
                                else -> stringResource(R.string.empty_no_recent)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                        )
                    } else {
                        AnimatedContent(
                            targetState = appsLayout,
                            transitionSpec = {
                                (fadeIn(tween(180)) + scaleIn(initialScale = 0.96f, animationSpec = tween(180)))
                                    .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)))
                            },
                            label = "layoutSwitch",
                        ) { layout ->
                            when (layout) {
                                AppsLayout.LIST -> {
                                    LazyColumn {
                                        itemsIndexed(filteredApps, key = { _, entry -> entry.packageName }) { index, entry ->
                                            StaggeredItem(index = index) {
                                                AppRow(
                                                    entry = entry,
                                                    repository = repository,
                                                    onLaunched = onDismiss,
                                                )
                                            }
                                        }
                                    }
                                }
                                AppsLayout.GRID -> {
                                    LazyVerticalGrid(
                                        columns = GridCells.Fixed(3),
                                        contentPadding = PaddingValues(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        itemsIndexed(filteredApps, key = { _, entry -> entry.packageName }) { index, entry ->
                                            StaggeredItem(index = index) {
                                                AppGridItem(
                                                    entry = entry,
                                                    repository = repository,
                                                    onLaunched = onDismiss,
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
            }
        }
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    val t = progress().coerceIn(0f, 1f)
                    alpha = ((0.28f - t) / 0.28f).coerceIn(0f, 1f)
                    val centerX = lerp(bubbleX + bubblePx / 2f, screenW / 2f, t)
                    val centerY = lerp(bubbleY + bubblePx / 2f, screenH / 2f, t)
                    translationX = centerX - iconPx / 2f
                    translationY = centerY - iconPx / 2f
                },
        )
    }
}

@Composable
private fun StaggeredItem(index: Int, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * ItemStaggerMs)
        visible = true
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(200),
        label = "itemAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (visible) 0f else 18f,
        animationSpec = tween(220),
        label = "itemOffset",
    )
    Box(
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            translationY = offsetY
        },
    ) {
        content()
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.cd_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun AppRow(entry: AppEntry, repository: AppRepository, onLaunched: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                launchApp(context, entry.packageName)
                onLaunched()
            }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            bitmap = remember(entry.packageName) { repository.iconBitmap(entry) },
            contentDescription = entry.label,
            modifier = Modifier.size(40.dp),
        )
        Column {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            entry.lastUsedMillis?.let { millis ->
                Text(
                    text = formatLastUsed(millis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppGridItem(entry: AppEntry, repository: AppRepository, onLaunched: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                launchApp(context, entry.packageName)
                onLaunched()
            }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            bitmap = remember(entry.packageName) { repository.iconBitmap(entry) },
            contentDescription = entry.label,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = entry.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

private fun launchApp(context: android.content.Context, packageName: String) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
    launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    launchIntent?.let { context.startActivity(it) }
}

/** Clip radii are in pre-scale pixels so a non-uniform scale still reads as a circle at rest. */
private class MorphClipShape(
    private val radiusX: Float,
    private val radiusY: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        return Outline.Rounded(
            RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                topLeftCornerRadius = CornerRadius(radiusX, radiusY),
                topRightCornerRadius = CornerRadius(radiusX, radiusY),
                bottomRightCornerRadius = CornerRadius(radiusX, radiusY),
                bottomLeftCornerRadius = CornerRadius(radiusX, radiusY),
            ),
        )
    }
}
