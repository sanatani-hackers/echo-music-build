package echo.music.iad1tya.playback

import androidx.media3.common.C
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.di.DownloadCache
import echo.music.iad1tya.di.PlayerCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * Utility class for querying and managing the two Media3 [SimpleCache] instances:
 *  - [playerCache]   : Streaming cache (LRU-evicted, written while playing a track).
 *  - [downloadCache] : Permanent download cache (NoOpEvictor, user-initiated downloads).
 *
 * Cache keys are **mediaId** (YouTube video IDs or local media IDs), NOT transient stream URLs.
 *
 * Inject this wherever cache status needs to be checked without directly coupling
 * to [MusicService].
 */
@Singleton
class CacheUtil
@Inject
constructor(
  @PlayerCache private val playerCache: SimpleCache,
  @DownloadCache private val downloadCache: SimpleCache,
  private val database: MusicDatabase,
) {

  // ---------------------------------------------------------------------------
  // Public suspend API (use from coroutines / ViewModels)
  // ---------------------------------------------------------------------------

  /**
   * Returns true if [mediaId] is fully cached in either [playerCache] or [downloadCache].
   *
   * Looks up [contentLength] from:
   * 1. [downloadCache] content metadata
   * 2. [playerCache] content metadata
   * 3. Room [FormatEntity.contentLength]
   *
   * Falls back to a span-presence check when no length is available (e.g. live streams
   * or tracks whose format has not been persisted yet).
   */
  suspend fun isFullyCached(mediaId: String): Boolean =
    withContext(Dispatchers.IO) {
      val contentLength = resolveContentLength(mediaId)
      if (contentLength > 0) {
        downloadCache.isCached(mediaId, 0, contentLength) ||
          playerCache.isCached(mediaId, 0, contentLength)
      } else {
        // Length unknown – treat any span as "cached enough to play"
        downloadCache.getCachedSpans(mediaId).isNotEmpty() ||
          playerCache.getCachedSpans(mediaId).isNotEmpty()
      }
    }

  /**
   * Returns true if [mediaId] has at least one cached byte-span in either cache.
   * Cheaper than [isFullyCached] – useful for showing a partial-cache indicator.
   */
  fun isPartiallyCachedSync(mediaId: String): Boolean =
    downloadCache.getCachedSpans(mediaId).isNotEmpty() ||
      playerCache.getCachedSpans(mediaId).isNotEmpty()

  /**
   * Returns true if [mediaId] is **fully** cached given a known [contentLength].
   * Synchronous – safe to call on the IO thread or inside [MusicService]'s resolving
   * data-source factory without launching a coroutine.
   *
   * Pass [contentLength] < 1 to fall back to span-presence only.
   */
  fun isFullyCachedSync(mediaId: String, contentLength: Long): Boolean =
    if (contentLength > 0) {
      downloadCache.isCached(mediaId, 0, contentLength) ||
        playerCache.isCached(mediaId, 0, contentLength)
    } else {
      downloadCache.getCachedSpans(mediaId).isNotEmpty() ||
        playerCache.getCachedSpans(mediaId).isNotEmpty()
    }

  /**
   * Returns true if [mediaId] is fully cached in [downloadCache] alone.
   * This mirrors the existing offline-playback check in [MusicService].
   */
  fun isInDownloadCache(mediaId: String, contentLength: Long): Boolean =
    if (contentLength > 0) {
      downloadCache.isCached(mediaId, 0, contentLength)
    } else {
      downloadCache.getCachedSpans(mediaId).isNotEmpty()
    }

  /**
   * Returns true if [mediaId] is fully cached in [playerCache] alone.
   * Used by [MusicService] to allow offline playback of streamed-but-not-downloaded songs.
   */
  fun isInPlayerCache(mediaId: String, contentLength: Long): Boolean =
    if (contentLength > 0) {
      playerCache.isCached(mediaId, 0, contentLength)
    } else {
      playerCache.getCachedSpans(mediaId).isNotEmpty()
    }

  // ---------------------------------------------------------------------------
  // Byte-count helpers (for UI storage indicators)
  // ---------------------------------------------------------------------------

  /** Total bytes cached for [mediaId] across both caches. */
  fun getCachedBytes(mediaId: String): Long {
    val downloadBytes = downloadCache.getCachedSpans(mediaId).sumOf { it.length }
    val playerBytes = playerCache.getCachedSpans(mediaId).sumOf { it.length }
    return downloadBytes + playerBytes
  }

  /** Bytes cached for [mediaId] in [playerCache] only. */
  fun getPlayerCachedBytes(mediaId: String): Long =
    playerCache.getCachedSpans(mediaId).sumOf { it.length }

  /** Bytes cached for [mediaId] in [downloadCache] only. */
  fun getDownloadCachedBytes(mediaId: String): Long =
    downloadCache.getCachedSpans(mediaId).sumOf { it.length }

  // ---------------------------------------------------------------------------
  // Eviction helpers
  // ---------------------------------------------------------------------------

  /**
   * Removes [mediaId] from [playerCache].
   * Safe to call from any thread; [SimpleCache] is thread-safe.
   */
  fun removeFromPlayerCache(mediaId: String) {
    playerCache.removeResource(mediaId)
  }

  // ---------------------------------------------------------------------------
  // Internal helpers
  // ---------------------------------------------------------------------------

  /**
   * Resolves the known content length for [mediaId] in priority order:
   * 1. [downloadCache] content metadata (most reliable for downloads)
   * 2. [playerCache] content metadata (written by Media3 during streaming)
   * 3. Room [FormatEntity.contentLength] (persisted after first stream resolution)
   *
   * Returns [C.LENGTH_UNSET] cast to Long (i.e. -1) when length is unknown.
   */
  internal suspend fun resolveContentLength(mediaId: String): Long {
    val downloadMeta =
      ContentMetadata.getContentLength(downloadCache.getContentMetadata(mediaId))
    if (downloadMeta != C.LENGTH_UNSET.toLong()) return downloadMeta

    val playerMeta =
      ContentMetadata.getContentLength(playerCache.getContentMetadata(mediaId))
    if (playerMeta != C.LENGTH_UNSET.toLong()) return playerMeta

    val dbLength =
      withContext(Dispatchers.IO) {
        database.format(mediaId).firstOrNull()?.contentLength ?: C.LENGTH_UNSET.toLong()
      }
    return dbLength
  }

  /**
   * Synchronous variant of [resolveContentLength].
   * Reads only from the two cache metadata stores – **does not** hit Room.
   * Use only in performance-sensitive paths (e.g. inside Media3 data-source factories).
   */
  internal fun resolveContentLengthSync(mediaId: String): Long {
    val downloadMeta =
      ContentMetadata.getContentLength(downloadCache.getContentMetadata(mediaId))
    if (downloadMeta != C.LENGTH_UNSET.toLong()) return downloadMeta

    val playerMeta =
      ContentMetadata.getContentLength(playerCache.getContentMetadata(mediaId))
    if (playerMeta != C.LENGTH_UNSET.toLong()) return playerMeta

    return C.LENGTH_UNSET.toLong()
  }
}
