package com.tribbloids.spookystuff.caching

import java.util.UUID

import com.tribbloids.spookystuff.{DirConf, SpookyContext}
import com.tribbloids.spookystuff.actions.Trace
import com.tribbloids.spookystuff.doc.{DocUtils, Fetched}
import com.tribbloids.spookystuff.utils.SpookyUtils
import org.apache.hadoop.fs.Path

/**
  * Backed by a WeakHashMap, the web cache temporarily store all trace -> Array[Page] until next GC.
  * Always enabled
  */
object DFSDocCache extends AbstractDocCache {

  DirConf // register with [[Submodules]].builderRegistry

  def cacheable(v: Seq[Fetched]): Boolean = {
    v.exists(v => v.cacheLevel.isInstanceOf[CacheLevel.DFS])
  }

  def getImpl(k: Trace, spooky: SpookyContext): Option[Seq[Fetched]] = {

    val pathStr = SpookyUtils.\\\(
      spooky.conf.dirConf.cache,
      spooky.conf.cacheFilePath(k).toString
    )

    val (earliestTime: Long, latestTime: Long) = getTimeRange(k.last, spooky)

    val pages = DocUtils.restoreLatest(
      new Path(pathStr),
      earliestTime,
      latestTime
    )(spooky)

    Option(pages)
  }

  def putImpl(k: Trace, v: Seq[Fetched], spooky: SpookyContext): this.type = {

    val pathStr = SpookyUtils.\\\(
      spooky.conf.dirConf.cache,
      spooky.conf.cacheFilePath(k).toString,
      UUID.randomUUID().toString
    )

    DocUtils.cache(v, pathStr)(spooky)
    this
  }
}