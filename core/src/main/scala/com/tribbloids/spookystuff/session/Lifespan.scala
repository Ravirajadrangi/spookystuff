package com.tribbloids.spookystuff.session

import com.tribbloids.spookystuff.utils.{IDMixin, NOTSerializable}
import org.apache.spark.TaskContext

import scala.language.implicitConversions
import scala.util.Try

/**
  * Java Deserialization only runs constructor of superclass
  */
abstract class Lifespan extends IDMixin with Serializable {

  def strategy: CleanupStrategy
  def ctxFactory: () => LifespanContext

  @transient lazy val ctx = ctxFactory.apply()
  def _id = {
    strategy.getCleanupBatchID(ctx)
  }

  {
    init()
  }
  protected def init() = {
    ctx //always generate context on construction

    if (!Cleanable.uncleaned.contains(_id)) {
      strategy.addCleanupHook (
        ctx,
        {
          () =>
            Cleanable.cleanSweep(_id)
        }
      )
    }
  }

  def readObject(in: java.io.ObjectInputStream): Unit = {
    in.defaultReadObject()
    init() //redundant?
  }

  def nameOpt: Option[String]
  override def toString: String = {
    val idStr = Try(_id.toString).getOrElse("[Error]")
    (nameOpt.toSeq ++ Seq(idStr)).mkString(":")
  }

  def isTask = strategy == Lifespan.Task
}

case class LifespanContext(
                            taskContextOpt: Option[TaskContext] = Option(TaskContext.get()),
                            thread: Thread = Thread.currentThread()
                          ) extends NOTSerializable {

}

abstract class CleanupStrategy extends Serializable {

  def addCleanupHook(
                      ctx: LifespanContext,
                      fn: () => Unit
                    ): Unit

  def getCleanupBatchID(ctx: LifespanContext): Any
}

object Lifespan {

  object Task extends CleanupStrategy {

    private def tc(ctx: LifespanContext) = {
      ctx.taskContextOpt.getOrElse(
        throw new UnsupportedOperationException("Not inside any Spark Task")
      )
    }

    override def addCleanupHook(ctx: LifespanContext, fn: () => Unit): Unit = {
      tc(ctx).addTaskCompletionListener {
        tc =>
          fn()
      }
    }

    case class ID(id: Long) {
      override def toString: String = s"Task-$id"
    }
    override def getCleanupBatchID(ctx: LifespanContext): ID = {
      ID(tc(ctx).taskAttemptId())
    }
  }

  object JVM extends CleanupStrategy {
    override def addCleanupHook(ctx: LifespanContext, fn: () => Unit): Unit = {
      sys.addShutdownHook {
        fn()
      }
    }

    case class ID(id: Long) {
      override def toString: String = s"Thread-$id"
    }
    override def getCleanupBatchID(ctx: LifespanContext): ID = {
      ID(ctx.thread.getId)
    }
  }

  object Auto extends CleanupStrategy {
    private def delegate(ctx: LifespanContext) = {
      ctx.taskContextOpt match {
        case Some(tc) =>
          Task
        case None =>
          JVM
      }
    }

    override def addCleanupHook(ctx: LifespanContext, fn: () => Unit): Unit = {
      delegate(ctx).addCleanupHook(ctx, fn)
    }

    override def getCleanupBatchID(ctx: LifespanContext): Any = {
      delegate(ctx).getCleanupBatchID(ctx)
    }
  }

  //CAUTION: keep the empty constructor! Kryo deserializer use them to initialize object
  case class Auto(
                   override val nameOpt: Option[String] = None,
                   ctxFactory: () => LifespanContext = () => LifespanContext()
                 ) extends Lifespan {
    def this() = this(None)

    override def strategy: CleanupStrategy = Auto
  }

  case class Task(
                   override val nameOpt: Option[String] = None,
                   ctxFactory: () => LifespanContext = () => LifespanContext()
                 ) extends Lifespan {
    def this() = this(None)

    override def strategy: CleanupStrategy = Task
  }

  case class JVM(
                  override val nameOpt: Option[String] = None,
                  ctxFactory: () => LifespanContext = () => LifespanContext()
                ) extends Lifespan {
    def this() = this(None)

    override def strategy: CleanupStrategy = JVM
  }
}