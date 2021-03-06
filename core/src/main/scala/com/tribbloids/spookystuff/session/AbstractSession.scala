package com.tribbloids.spookystuff.session

import java.util.Date
import java.util.concurrent.TimeUnit

import com.tribbloids.spookystuff.actions._
import com.tribbloids.spookystuff.session.python.PythonDriver
import com.tribbloids.spookystuff.utils.SpookyUtils
import com.tribbloids.spookystuff.{Const, SpookyContext, SpookyException}
import org.apache.spark.TaskContext
import org.openqa.selenium.Dimension
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer

//case object SessionRelay extends MessageRelay[Session] {
//
//  case class M(
//                startTime: Long,
//                backtrace: Seq[Action],
//                TaskContext: Option[TaskContextRelay.M]
//              ) extends Message
//
//  override def toMessage(v: Session): M = {
//    M(
//      v.startTime,
//      v.backtrace,
//      v.taskContextOpt.map (tc => TaskContextRelay.toMessage(tc))
//    )
//  }
//}
//
//case object TaskContextRelay extends MessageRelay[TaskContext] {
//
//  case class M(
//                attemptNumber: Int
//              ) extends Message
//
//  override def toMessage(v: TaskContext): M = {
//    M(
//      v.attemptNumber()
//    )
//  }
//}

sealed abstract class AbstractSession(val spooky: SpookyContext) extends LocalCleanable {

  spooky.metrics.sessionInitialized += 1
  val startTime: Long = new Date().getTime
  val backtrace: ArrayBuffer[Action] = ArrayBuffer()

  def webDriver: CleanWebDriver
  def pythonDriver: PythonDriver

  def taskContextOpt: Option[TaskContext] = lifespan.ctx.taskContextOpt
}

abstract class NoDriverException(val str: String) extends SpookyException(str: String)
object NoWebDriverException extends NoDriverException("INTERNAL ERROR: should initialize driver automatically")
object NoPythonDriverException extends NoDriverException("INTERNAL ERROR: should initialize driver automatically")

/**
  * the only implementation
  * should be manually cleaned By ActionLike, so don't set lifespan unless absolutely necessary
  */
class Session(
               override val spooky: SpookyContext,
               override val lifespan: Lifespan = new Lifespan.JVM()
             ) extends AbstractSession(spooky){

  @volatile private var _webDriverOpt: Option[CleanWebDriver] = None
  def webDriverOpt = _webDriverOpt.filter(!_.isCleaned)
  //throwing error instead of lazy creation is required for restarting timer
  def webDriver = webDriverOpt.getOrElse{
    throw NoWebDriverException
  }

  def getOrProvisionWebDriver: CleanWebDriver = {
    webDriverOpt.getOrElse {
      SpookyUtils.retry(Const.localResourceLocalRetries) {
        SpookyUtils.withDeadline(Const.sessionInitializationTimeout) {
          val driver = spooky.conf.webDriverFactory.dispatch(this)
          spooky.metrics.webDriverDispatched += 1
          //      try {
          driver.manage().timeouts()
            .implicitlyWait(spooky.conf.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)
            .pageLoadTimeout(spooky.conf.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)
            .setScriptTimeout(spooky.conf.remoteResourceTimeout.toSeconds, TimeUnit.SECONDS)

          val resolution = spooky.conf.browserResolution
          if (resolution != null) driver.manage().window().setSize(new Dimension(resolution._1, resolution._2))

          _webDriverOpt = Some(driver)
          //      }            //TODO: these are no longer required, if a driver is get for multiple times the previous one will be automatically scuttled
          //      finally {
          //        if (!successful){
          //          driver.close()
          //          driver.quit()
          //          spooky.metrics.driverReleased += 1
          //        }
          //      }
          driver
        }
      }
    }
  }

  @volatile private var _pythonDriverOpt: Option[PythonDriver] = None
  def pythonDriverOpt = _pythonDriverOpt.filter(!_.isCleaned)
  //throwing error instead of lazy creation is required for restarting timer
  def pythonDriver = pythonDriverOpt.getOrElse{
    throw NoPythonDriverException
  }

  def getOrProvisionPythonDriver: PythonDriver = {
    pythonDriverOpt.getOrElse {
      SpookyUtils.retry(Const.localResourceLocalRetries) {

        SpookyUtils.withDeadline(Const.sessionInitializationTimeout) {
          val driver = spooky.conf.pythonDriverFactory.dispatch(this)
          spooky.metrics.pythonDriverDispatched += 1

          _pythonDriverOpt = Some(driver)
          driver
        }
      }
    }
  }

  def withDriversDuring[T](f: => T, n: Int = 3): T = {
    try {
      assert(n >= 0)
      f
    }
    catch {
      case NoWebDriverException =>
        LoggerFactory.getLogger(this.getClass).debug(s"Web driver doesn't exist, creating ... $n time(s) left")
        getOrProvisionWebDriver
        withDriversDuring(f, n - 1)
      case NoPythonDriverException =>
        LoggerFactory.getLogger(this.getClass).debug(s"Python driver doesn't exist, creating ... $n time(s) left")
        getOrProvisionPythonDriver
        withDriversDuring(f, n - 1)
      case e: Throwable =>
        throw e
    }
  }

  override def cleanImpl(): Unit = {
    Option(spooky.conf.webDriverFactory).foreach{
      factory =>
        factory.release(this)
        spooky.metrics.webDriverReleased += 1
    }
    Option(spooky.conf.pythonDriverFactory).foreach{
      factory =>
        factory.release(this)
        spooky.metrics.pythonDriverReleased += 1
    }
    spooky.metrics.sessionReclaimed += 1
  }
}