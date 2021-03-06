package com.tribbloids.spookystuff.utils

import com.tribbloids.spookystuff.utils.TreeException.TreeNodeView
import org.apache.spark.sql.catalyst.trees.TreeNode

import scala.util.{Failure, Success, Try}

object TreeException {

  case class TreeNodeView(self: Throwable) extends TreeNode[TreeNodeView] {
    override def children: Seq[TreeNodeView] = {
      val result = self match {
        case v: TreeException =>
          v.causes.map(TreeNodeView)
        case _ =>
          val eOpt = Option(self).flatMap(
            v =>
              Option(v.getCause)
          )
          eOpt.map(TreeNodeView).toSeq
      }
      result.sortBy(_.simpleString())
    }

    override def simpleString(): String = {
      self match {
        case v: TreeException =>
          v.nodeMessage
        case _ =>
          self.getClass.getName + ": " + self.getMessage
      }
    }
  }

  def effectiveAgg(
                    agg: Seq[Throwable] => TreeException = es => MultiCauseWrapper(causes = es),
                    extra: Seq[Throwable] = Nil,
                    expandUnary: Boolean = false
                  ): Seq[Throwable] => Throwable = {

    {
      seq =>
        val flat = seq.flatMap {
          case MultiCauseWrapper(causes) =>
            causes
          case v@ _ => Seq(v)
        }
        val all = extra.flatMap(v => Option(v)) ++ flat
        if (expandUnary && all.size == 1) all.head
        else agg(all)
    }
  }

  def &&&[T](
              trials: Seq[Try[T]],
              agg: Seq[Throwable] => TreeException = es => MultiCauseWrapper(causes = es),
              extra: Seq[Throwable] = Nil,
              expandUnary: Boolean = false
            ): Seq[T] = {

    val es = trials.collect{
      case Failure(e) => e
    }
    if (es.isEmpty) {
      trials.map(_.get)
    }
    else {
      val _agg = effectiveAgg(agg, extra, expandUnary)
      throw _agg(es)
    }
  }

  def |||[T](
              trials: Seq[Try[T]],
              agg: Seq[Throwable] => TreeException = es => MultiCauseWrapper(causes = es),
              extra: Seq[Throwable] = Nil,
              expandUnary: Boolean = false
            ): Seq[T] = {

    if (trials.isEmpty) return Nil

    val results = trials.collect{
      case Success(e) => e
    }

    if (results.isEmpty) {
      val es = trials.collect{
        case Failure(e) => e
      }
      val _agg = effectiveAgg(agg, extra, expandUnary)
      throw _agg(es)
    }
    else {
      results
    }
  }

  class Node(
              val nodeMessage: String = "",
              val cause: Throwable = null
            ) extends TreeException {

    override def causes: Seq[Throwable] = {
      cause match {
        case MultiCauseWrapper(causes) => causes
        case _ =>
          Option(cause).toSeq
      }
    }
  }

  case class MultiCauseWrapper(
                                override val causes: Seq[Throwable] = Nil
                              ) extends TreeException {

    val nodeMessage: String = s"[CAUSED BY ${causes.size} EXCEPTION(S)]"
  }
}

trait TreeException extends Throwable {

  def causes: Seq[Throwable] = Nil

  lazy val treeNodeView = TreeNodeView(this)

  override def getMessage: String = treeNodeView.toString()

  override def getCause: Throwable = causes.headOption.orNull

  def nodeMessage: String
}
