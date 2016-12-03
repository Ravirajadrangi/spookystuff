package com.tribbloids.spookystuff.session.python

import java.io.File
import java.util.regex.Pattern

import com.tribbloids.spookystuff.session.{Lifespan, LocalCleanable}
import com.tribbloids.spookystuff.utils.SpookyUtils
import com.tribbloids.spookystuff.{PyException, PyInterpreterException, SpookyContext}
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object PythonDriver {

  import com.tribbloids.spookystuff.utils.SpookyViews._

  final val DEFAULT_PYTHON_PATH = System.getProperty("user.home") \\ ".spookystuff" \\ "pythonpath"
  final val MODULE_NAME = "pyspookystuff"
  final val MODULE_RESOURCE = "com/tribbloids/" :/ MODULE_NAME
  final val PYTHON_LIB_RESOURCE = "com/tribbloids/spookystuff/lib/python"

  final val errorPattern: Pattern = Pattern.compile(".*(Error|Exception):.*$")
  final val syntaxErrorPattern: Pattern = Pattern.compile(".*(SyntaxError):.*$")

  import com.tribbloids.spookystuff.utils.SpookyViews._

  /**
    * DO NOT ATTEMPT TO SIMPLIFY IMPLEMENTATION!
    * com.tribbloids.pyspookystuff exists in both /class & /test-class and any attempt to merge it
    * with com.tribbloids.spookystuff.lib.python will results in test classes being retrieved.
    */
  lazy val deploy: String = {
    val pythonPath: String = PythonDriver.DEFAULT_PYTHON_PATH // extract pyspookystuff from resources temporarily on workers
    val modulePath = pythonPath \\ PythonDriver.MODULE_NAME

    val pythonDir = new File(pythonPath)
    FileUtils.deleteQuietly(pythonDir)

    val libResourceOpt = SpookyUtils.getCPResource(PythonDriver.PYTHON_LIB_RESOURCE)
    libResourceOpt.foreach {
      resource =>
        SpookyUtils.extractResource(resource, pythonPath)
    }

    val moduleResourceOpt = SpookyUtils.getCPResource(PythonDriver.MODULE_RESOURCE)
    moduleResourceOpt.foreach {
      resource =>
        //        SpookyUtils.asynchIfNotExist(modulePath){

        SpookyUtils.extractResource(resource, modulePath)
      //        }
    }
    pythonPath
  }

  def NO_RETURN_VALUE: String = {
    "*!?no returned value!?*"
  }

  def EXECUTION_RESULT: String = {
    "*!?execution result!?*"
  }
}

/**
  * Created by peng on 01/08/16.
  */
//TODO: not reusing Python worker for spark, is it not optimal?
class PythonDriver(
                    val executable: String = "python",
                    val autoImports: String =
                    """
                      |import os
                      |from __future__ import print_function
                    """.trim.stripMargin,
                    override val lifespan: Lifespan = new Lifespan.Auto()
                  ) extends PythonProcess(executable) with LocalCleanable {

  /**
    * NOT thread safe
    */
  lazy val historyLines: ArrayBuffer[String] = ArrayBuffer.empty
  lazy val pendingLines: ArrayBuffer[String] = ArrayBuffer.empty
  lazy val importedLines: scala.collection.mutable.Set[String] = scala.collection.mutable.Set.empty

  {
    val pythonPath = PythonDriver.deploy

    this.open

    this.lazyImport(
      s"""
         |import sys
         |sys.path.append('$pythonPath')
         |$autoImports
       """.stripMargin
        .split("\n")
    )
  }

  import PythonDriver._

  //avoid reopening!
  override lazy val open: Unit = {
    super.open()
  }

  override def cleanImpl(): Unit = {
    Try {
      SpookyUtils.retry(10, 1000) {
        if (process.isAlive) {
          try {
            this.interpret("exit()")
          }
          catch {
            case e: PyException =>
          }
          Thread.sleep(1000)
          assert(!process.isAlive)
        }
      }
    }
      .orElse(
        Try(this.close())
      )
      .getOrElse(this.interrupt())
  }

  /**
    * Checks if there is a syntax error or an exception
    * From Zeppelin PythonInterpreter
    *
    * @return true if syntax error or exception has happened
    */
  private def pythonErrorIn(lines: Seq[String]): Boolean = {

    val indexed = lines.zipWithIndex
    val tracebackRows: Seq[Int] = indexed.filter(_._1.startsWith("Traceback ")).map(_._2)
    val errorRows: Seq[Int] = indexed.filter {
      v =>
        val matcher = errorPattern.matcher(v._1)
        matcher.find
    }.map(_._2)
    val syntaxErrorRow: Seq[Int] = indexed.filter {
      v =>
        val matcher = syntaxErrorPattern.matcher(v._1)
        matcher.find
    }.map(_._2)

    if ((tracebackRows.nonEmpty && errorRows.nonEmpty) || syntaxErrorRow.nonEmpty) true
    else false

    //    tracebackRows.foreach {
    //      row =>
    //        val errorRowOpt = errorRows.find(_ > row)
    //        errorRowOpt.foreach {
    //          errorRow =>
    //            val tracebackDetails = lines.slice(row +1, errorRow)
    //            if (tracebackDetails.forall(_.startsWith(""))
    //        }
    //    }
  }

  final def PROMPTS = "^(>>> |\\.\\.\\. )+"

  def removePrompts(str: String): String = {
    str.stripPrefix("\r").replaceAll(PROMPTS, "")
  }

  override def pyOutputLog(line: String) = {
    s"$logPrefix $line"
  }

  private def _interpret(code: String, spookyOpt: Option[SpookyContext] = None): Array[String] = {
    val indentedCode = code.split('\n').filter(_.nonEmpty).map("\t" + _).mkString("\n")

    LoggerFactory.getLogger(this.getClass).info(s">>> $logPrefix INPUT >>>\n" + indentedCode)

    val historyCodeOpt = if (this.historyLines.isEmpty) None
    else {
      val combined = "\n" + this.historyLines.mkString("\n").stripPrefix("\n")
      val indented = combined.split('\n').map(v => "\t" + v).mkString("\n")

      Some(indented)
    }
    val rows = try {
      // DO NOT DELETE! some Python Drivers are accessed by many threads (e.g. ProxyManager)
      val output = this.synchronized {
        this.sendAndGetResult(code)
      }
      output
        .split("\n")
        .map(
          removePrompts
        )
    }
    catch {
      case e: Throwable =>
        spookyOpt.foreach(
          _.metrics.pythonInterpretationError += 1
        )
        val ee = new PyException(
          indentedCode,
          this.outputBuffer,
          e,
          historyCodeOpt
        )
        throw ee
    }

    //    if (rows.exists(_.nonEmpty)) {
    //      LoggerFactory.getLogger(this.getClass).info(s"$$$$$$ PYTHON-${this.taskOrThread.id} OUTPUT ===============\n" + rows.mkString("\n"))
    //    }
    //    else {
    //      LoggerFactory.getLogger(this.getClass).info(s"$$$$$$ PYTHON-${this.taskOrThread.id} [NO OUTPUT] ===============\n" + rows.mkString("\n"))
    //    }

    if (pythonErrorIn(rows)) {
      spookyOpt.foreach(
        _.metrics.pythonInterpretationError += 1
      )
      val ee = new PyInterpreterException(
        indentedCode,
        rows.mkString("\n"),
        historyCodeOpt
      )
      throw ee
    }

    spookyOpt.foreach(
      _.metrics.pythonInterpretationSuccess += 1
    )
    rows
  }

  //TODO: due to unchecked use of thread-unsafe mutable objects (e.g. ArrayBuffer), all following APIs are rendered synchronized.
  def interpret(code: String, spookyOpt: Option[SpookyContext] = None): Array[String] = this.synchronized{
    def lazyCode = pendingLines.mkString("\n")
    val allCode = lazyCode + "\n" + code
    val result = _interpret(allCode, spookyOpt)
    this.historyLines += allCode
    this.pendingLines.clear()
    result
  }

  def eval(
            code: String,
            resultVarOpt: Option[String] = None,
            spookyOpt: Option[SpookyContext] = None
          ): (Seq[String], Option[String]) = this.synchronized{
    resultVarOpt match {
      case None =>
        val _code =
          s"""
             |$code
          """.trim.stripMargin
        val rows = interpret(_code, spookyOpt)
        rows.toSeq -> None
      case Some(resultVar) =>
        val _code =
          s"""
             |$resultVar=None
             |$code
             |print('$EXECUTION_RESULT')
             |if $resultVar:
             |    print($resultVar)
             |else:
             |    print('$NO_RETURN_VALUE')
             |
             |del($resultVar)
          """.trim.stripMargin
        val rows = interpret(_code, spookyOpt).toSeq
        val splitterIndex = rows.zipWithIndex.find(_._1 == EXECUTION_RESULT).get._2
        val split = rows.splitAt(splitterIndex)

        val _result = split._2.slice(1, Int.MaxValue).mkString("\n")
        val resultOpt = if (_result == NO_RETURN_VALUE) None
        else Some(_result)

        split._1 -> resultOpt
    }
  }

  def lazyInterpret(code: String): Unit = this.synchronized{
    pendingLines += code
  }

  def lazyImport(codes: Seq[String]): Unit = this.synchronized{
    codes
      .map(_.trim)
      .foreach {
        code =>
          if (!importedLines.contains(code)) {
            lazyInterpret(code)
            importedLines += code
          }
      }
  }
}
