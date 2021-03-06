package com.tribbloids.spookystuff.uav.telemetry

import com.tribbloids.spookystuff.caching.Memoize
import com.tribbloids.spookystuff.session._
import com.tribbloids.spookystuff.session.python.PyRef
import com.tribbloids.spookystuff.uav.dsl.LinkFactory
import com.tribbloids.spookystuff.uav.spatial._
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.mavlink.MAVLink
import com.tribbloids.spookystuff.uav.{ReinforcementDepletedException, UAVConf}
import com.tribbloids.spookystuff.utils.{SpookyUtils, TreeException}
import com.tribbloids.spookystuff.{SpookyContext, caching}
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
  * Created by peng on 24/01/17.
  */
object Link {

  // connStr -> (link, isBusy)
  // only 1 allowed per connStr, how to enforce?
  val existing: caching.ConcurrentMap[UAV, Link] = caching.ConcurrentMap()

  def trySelect(
                 uavs: Seq[UAV],
                 session: Session,
                 prefer: Seq[Link] => Option[Link] = {
                   vs =>
                     vs.headOption
                 },
                 recommissionWithNewProxy: Boolean = true
               ): Try[Link] = {

    val sessionThreadOpt = Some(session.lifespan.ctx.thread)

    val threadLocalOpt = {
      val results = Link.existing.values.toList.filter{
        v =>
          val id1Opt = v.usedByThreadOpt.map(_.getId)
          val id2Opt = sessionThreadOpt.map(_.getId)
          val lMatch = (id1Opt, id2Opt) match {
            case (Some(tc1), Some(tc2)) if tc1 == tc2 => true
            case _ => false
          }
          lMatch
      }
      assert(results.size <= 1, "Multiple Links cannot share task context or thread")
      val result = results.headOption
      result.foreach(_.usedByThread = session.lifespan.ctx.thread)
      result
    }

    val resultOpt = threadLocalOpt.orElse {
      val links = uavs.map(_.getLink(session.spooky))

      this.synchronized {
        val available = links.filter(v => v.isAvailable)
        val selectedOpt = prefer(available)
        selectedOpt.foreach(_.usedByThread = session.lifespan.ctx.thread)
        selectedOpt
      }
    }

    resultOpt match {
      case Some(link) =>
        val result = if (recommissionWithNewProxy) {
          val factory = session.spooky.submodule[UAVConf].linkFactory
          link.recommission(factory)
        }
        else {
          link
        }
        Success(result)
      case None =>
        val info = if (Link.existing.isEmpty) {
          val msg = s"No telemetry Link for ${uavs.mkString("[", ", ", "]")}, existing links are:"
          val hint = Link.existing.keys.toList.mkString("[", ", ", "]")
          msg + "\n" + hint
        }
        else {
          "All telemetry Links are busy:\n" +
            Link.existing.values.map {
              link =>
                link.statusString
            }
              .mkString("\n")
        }
        Failure(
          new ReinforcementDepletedException(info)
        )
    }
  }

  def allConflicts = {
    Seq(
      Try(PyRef.sanityCheck()),
      Try(MAVLink.sanityCheck())
    ) ++
      DetectResourceConflict.conflicts
  }
}

trait Link extends LocalCleanable {

  val uav: UAV

  @volatile protected var _spooky: SpookyContext = _
  def spookyOpt = Option(_spooky)

  @volatile protected var _factory: LinkFactory = _
  def factoryOpt = Option(_factory)

  lazy val runOnce: Unit = {
    spookyOpt.get.metrics.linkCreated += 1
  }

  def setContext(
                  spooky: SpookyContext = this._spooky,
                  factory: LinkFactory = this._factory
                ): this.type = this.synchronized{

    try {
      _spooky = spooky
      _factory = factory
      //      _taskContext = taskContext
      spookyOpt.foreach(v => runOnce)

      val inserted = Link.existing.getOrElseUpdate(uav, this)
      assert(inserted eq this, s"Multiple Links created for drone $uav")

      this
    }
    catch {
      case e: Throwable =>
        this.clean()
        throw e
    }
  }

  @volatile protected var _usedByThread: Thread = _
  def usedByThreadOpt = Option(_usedByThread)
  def usedByThread = usedByThreadOpt.get
  def usedByThread_=(
                      c: Thread
                    ): this.type = {

    if (usedByThreadOpt.exists(v => v.getId == c.getId)) this
    else {
      this.synchronized{
        assert(isNotUsedByThread, s"Cannot be used by thread ${c.getName}: $statusString")
        this._usedByThread = c
        this
      }
    }
  }

  var isDryrun = false
  //finalizer may kick in and invoke it even if its in Link.existing
  override protected def cleanImpl(): Unit = {

    val existingOpt = Link.existing.get(uav)
    existingOpt.foreach {
      v =>
        if (v eq this)
          Link.existing -= uav
        else {
          if (!isDryrun) throw new AssertionError("THIS IS NOT A DRYRUN OBJECT! SO ITS CREATED ILLEGALLY!")
        }
    }
    spookyOpt.foreach {
      spooky =>
        spooky.metrics.linkDestroyed += 1
    }
  }

  var isConnected: Boolean = false
  final def connectIfNot(): Unit = this.synchronized{
    if (!isConnected) {
      _connect()
    }
    isConnected = true
  }
  protected def _connect(): Unit

  final def disconnect(): Unit = this.synchronized{
    _disconnect()
    isConnected = false
  }
  protected def _disconnect(): Unit

  private def connectRetries: Int = spookyOpt
    .map(
      spooky =>
        spooky.conf.submodule[UAVConf].fastConnectionRetries
    )
    .getOrElse(UAVConf.FAST_CONNECTION_RETRIES)

  @volatile var lastFailureOpt: Option[(Throwable, Long)] = None

  protected def detectConflicts(): Unit = {}

  /**
    * A utility function that all implementation should ideally be enclosed
    * All telemetry are inheritively unstable, so its better to reconnect if anything goes wrong.
    * after all retries are exhausted will try to detect URL conflict and give a report as informative as possible.
    */
  def retry[T](n: Int = connectRetries, interval: Long = 0, silent: Boolean = false)(
    fn: =>T
  ): T = {
    try {
      SpookyUtils.retry(n, interval, silent) {
        try {
          connectIfNot()
          fn
        }
        catch {
          case e: Throwable =>
            disconnect()
            val conflicts = Seq(Failure[Unit](e)) ++
              Seq(Try(detectConflicts())) ++
              Link.allConflicts
            val afterDetection = {
              try {
                TreeException.&&&(conflicts)
                e
              }
              catch {
                case ee: Throwable =>
                  ee
              }
            }
            if (!silent) LoggerFactory.getLogger(this.getClass).warn(s"CONNECTION TO $uav FAILED!", afterDetection)
            throw afterDetection
        }
      }
    }
    catch {
      case e: Throwable =>
        lastFailureOpt = Some(e -> System.currentTimeMillis())
        throw e
    }
  }

  /**
    * set true to block being used by another thread
    * This should be a Future to indicate 3 states:
    * Completed(true): don't think about it, just use another link or (if depleted) report error.
    * Completed(false): use it immediately.
    * Pending: wait for result, then decide.
    */
  @volatile var isBooked: Boolean = false

  private def blacklistDuration: Long = spookyOpt
    .map(
      spooky =>
        spooky.conf.submodule[UAVConf].slowConnectionRetryInterval
    )
    .getOrElse(UAVConf.BLACKLIST_RESET_AFTER)
    .toMillis

  def isNotBlacklisted: Boolean = !lastFailureOpt.exists {
    tt =>
      System.currentTimeMillis() - tt._2 <= blacklistDuration
  }

  def isNotUsedByThread: Boolean = {
    usedByThreadOpt.forall(v => !v.isAlive)
  }

  def isAvailable: Boolean = {
    !isBooked && isNotBlacklisted && isNotUsedByThread
  }

  def statusString: String = {

    val strs = ArrayBuffer[String]()
    if (isBooked)
      strs += "booked"
    if (!isNotBlacklisted)
      strs += s"unreachable for ${(System.currentTimeMillis() - lastFailureOpt.get._2).toDouble / 1000}s" +
        s" (${lastFailureOpt.get._1.getClass.getSimpleName})"
    if (!isNotUsedByThread)
      strs += s"used by ${_usedByThread}"

    s"Link $uav is " + {
      if (isAvailable) {
        assert(strs.isEmpty)
        "available"
      }
      else {
        strs.mkString(" & ")
      }
    }
  }

  def coFactory(another: Link): Boolean
  def recommission(
                    factory: LinkFactory
                  ): Link = {

    val neo = factory.apply(uav)
    val result = if (coFactory(neo)) {
      LoggerFactory.getLogger(this.getClass).info {
        s"Reusing existing link for $uav"
      }
      neo.isDryrun = true
      neo.clean(silent = true)
      this
    }
    else {
      LoggerFactory.getLogger(this.getClass).info {
        s"Recreating link with new factory ${factory.getClass.getSimpleName}"
      }
      this.clean(silent = true)
      neo
    }
    result.setContext(
      this._spooky,
      factory
    )
    result
  }

  //================== COMMON API ==================

  // will retry 6 times, try twice for Vehicle.connect() in python, if failed, will restart proxy and try again (3 times).
  // after all attempts failed will stop proxy and add endpoint into blacklist.
  // takes a long time.
  def connect(): Unit = {
    retry()(Unit)
  }

  // Most telemetry support setting up multiple landing site.
  protected def _getHome: Location
  protected lazy val home: Location = {
    retry(5){
      _getHome
    }
  }

  protected def _getCurrentLocation: Location
  protected object CurrentLocation extends Memoize[Unit, Location]{
    override def f(v: Unit): Location = {
      retry(5) {
        _getCurrentLocation
      }
    }
  }

  def status(expireAfter: Long = 1000) = {
    val current = CurrentLocation.getIfNotExpire((), expireAfter)
    LinkStatus(uav, home, current)
  }

  //====================== Synchronous API ======================
  // TODO this should be abandoned and mimic by Asynch API

  val synch: SynchronousAPI
  abstract class SynchronousAPI {
    def testMove: String

    def clearanceAlt(alt: Double): Unit
    def move(location: Location): Unit
  }

  //====================== Asynchronous API =====================

  //  val Asynch: AsynchronousAPI
  //  abstract class AsynchronousAPI {
  //    def move(): Unit
  //  }
}
