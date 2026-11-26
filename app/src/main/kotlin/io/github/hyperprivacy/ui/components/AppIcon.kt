package io.github.hyperprivacy.ui.components

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-memory icon cache; icons are decoded once, off the main thread, at a fixed size. */
private object AppIconCache {
    private const val CAPACITY = 160
    private const val ICON_PX = 144

    private val cache = LruCache<String, ImageBitmap>(CAPACITY)

    fun peek(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? = peek(packageName) ?: withContext(
        Dispatchers.IO,
    ) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
        }.getOrNull()?.also { cache.put(packageName, it) }
    }
}

private const val ICON_CORNER_PERCENT = 24

/** An app's launcher icon. Decorative: the app name is always shown next to it. */
@Composable
internal fun AppIcon(packageName: String, size: Dp, modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val icon by produceState(AppIconCache.peek(packageName), packageName) {
        value = AppIconCache.load(appContext, packageName)
    }
    val shape = RoundedCornerShape(ICON_CORNER_PERCENT)
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size).clip(shape))
    } else {
        Box(
            modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}
