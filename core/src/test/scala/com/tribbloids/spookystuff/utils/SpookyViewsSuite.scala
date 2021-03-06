package com.tribbloids.spookystuff.utils

import com.tribbloids.spookystuff.SpookyEnvFixture
import com.tribbloids.spookystuff.testutils.TestHelper
import org.apache.spark.{SparkEnv, TaskContext}

import scala.util.Random

/**
  * Created by peng on 16/11/15.
  */
class SpookyViewsSuite extends SpookyEnvFixture {

  import SpookyViews._
  import org.scalatest.Matchers._

  it("multiPassFlatMap should yield same result as flatMap") {

    val src = sc.parallelize(1 to 100).persist()

    val counter = sc.accumulator(0)
    val counter2 = sc.accumulator(0)

    val res1 = src.flatMap(v => Seq(v, v*2, v*3))
    val res2 = src.multiPassFlatMap{
      v =>
        val rand = Random.nextBoolean()
        counter2 +=1
        if (rand) {
          counter +=1
          Some(Seq(v, v*2, v*3))
        }
        else None
    }

    assert(res1.collect().toSeq == res2.collect().toSeq)
    assert(counter.value == 100)
    assert(counter2.value > 100)
  }

  it("TraversableLike.filterByType should work on primitive types") {

    assert(Seq(1, 2.2, "a").filterByType[Int].get == Seq(1))
    assert(Seq(1, 2.2, "a").filterByType[java.lang.Integer].get == Seq(1: java.lang.Integer))
    assert(Seq(1, 2.2, "a").filterByType[Double].get == Seq(2.2))
    assert(Seq(1, 2.2, "a").filterByType[java.lang.Double].get == Seq(2.2: java.lang.Double))
    assert(Seq(1, 2.2, "a").filterByType[String].get == Seq("a"))

    assert(Set(1, 2.2, "a").filterByType[Int].get == Set(1))
    assert(Set(1, 2.2, "a").filterByType[java.lang.Integer].get == Set(1: java.lang.Integer))
    assert(Set(1, 2.2, "a").filterByType[Double].get == Set(2.2))
    assert(Set(1, 2.2, "a").filterByType[java.lang.Double].get == Set(2.2: java.lang.Double))
    assert(Set(1, 2.2, "a").filterByType[String].get == Set("a"))
  }

  it("Array.filterByType should work on primitive types") {

    assert(Array(1, 2.2, "a").filterByType[Int].toSeq == Seq(1))
    assert(Array(1, 2.2, "a").filterByType[java.lang.Integer].toSeq == Seq(1: java.lang.Integer))
    assert(Array(1, 2.2, "a").filterByType[Double].toSeq == Seq(2.2))
    assert(Array(1, 2.2, "a").filterByType[java.lang.Double].toSeq == Seq(2.2: java.lang.Double))
    assert(Array(1, 2.2, "a").filterByType[String].toSeq == Seq("a"))
  }

  val nullStr = null: String
  it(":/ can handle null component") {

    assert(nullStr :/ nullStr :/ "abc" :/ null :/ null == "abc")
  }

  it("\\\\ can handle null component") {

    assert(nullStr \\ nullStr \\ "abc" \\ null \\ null == "abc")
  }

  it("mapPerExecutorThread will run properly") {
    val result = sc.mapPerExecutorCore {
      TestHelper.assert(!TaskContext.get().isRunningLocally())
      SparkEnv.get.blockManager.blockManagerId ->
        TaskContext.getPartitionId()
    }
      .collect()
    assert(result.length == sc.defaultParallelism, result.mkString("\n"))
    assert(result.map(_._1).distinct.length == TestHelper.numWorkers, result.mkString("\n"))
    assert(result.map(_._2).distinct.length == sc.defaultParallelism, result.mkString("\n"))
    result.foreach(println)
  }

  it("mapPerWorker will run properly") {
    val result = sc.mapPerWorker {
      TestHelper.assert(!TaskContext.get().isRunningLocally())
      SparkEnv.get.blockManager.blockManagerId ->
        TaskContext.getPartitionId()
    }
      .collect()
    assert(result.length == TestHelper.numWorkers, result.mkString("\n"))
    assert(result.map(_._1).distinct.length == TestHelper.numWorkers, result.mkString("\n"))
    assert(result.map(_._2).distinct.length == TestHelper.numWorkers, result.mkString("\n"))
    result.foreach(println)
  }

  it("mapPerCore will run properly") {
    val result = sc.mapPerCore {
      SparkEnv.get.blockManager.blockManagerId ->
        TaskContext.getPartitionId()
    }
      .collect()
    assert(result.length == sc.defaultParallelism + 1, result.mkString("\n"))
    assert(result.map(_._1).distinct.length == TestHelper.numComputers, result.mkString("\n"))
    assert(result.map(_._2).distinct.length == sc.defaultParallelism + 1, result.mkString("\n"))
    result.foreach(println)
  }

  it("mapPerComputer will run properly") {
    val result = sc.mapPerComputer {
      SparkEnv.get.blockManager.blockManagerId ->
        TaskContext.getPartitionId()
    }
      .collect()
    //+- 1 is for executor lost tolerance
    assert(result.length === TestHelper.numComputers +- 1, result.mkString("\n"))
    assert(result.map(_._1).distinct.length === TestHelper.numComputers +- 1, result.mkString("\n"))
    assert(result.map(_._2).distinct.length === TestHelper.numComputers +- 1, result.mkString("\n"))
    result.foreach(println)
  }

  it("result of allTaskLocationStrs can be used as partition's preferred location") {
    //TODO: change to more succinct ignore
    if (org.apache.spark.SPARK_VERSION.replaceAllLiterally(".","").toInt >= 160) {
      val tlStrs = sc.allTaskLocationStrs
      tlStrs.foreach(println)
      val length = tlStrs.size
      val seq: Seq[((Int, String), Seq[String])] = (1 to 100).map {
        i =>
          val nodeName = tlStrs(Random.nextInt(length))
          (i -> nodeName) -> Seq(nodeName)
      }

      val created = sc.makeRDD[(Int, String)](seq)
      //TODO: this RDD is extremely partitioned, can we use coalesce to reduce it?
      val conditions = created.map {
        tuple =>
          tuple._2 == SpookyUtils.getTaskLocationStr
      }
        .collect()
      assert(conditions.count(identity) == 100)
    }
  }

  it("interpolate can use common character as delimiter") {

    val original = "ORA'{TEST}"
    val interpolated = original.interpolate("'"){
      v =>
        "Replaced"
    }
    assert(interpolated == "ORAReplaced")
  }

  it("interpolate can use special regex character as delimiter") {

    val original = "ORA${TEST}"
    val interpolated = original.interpolate("$"){
      v =>
        "Replaced"
    }
    assert(interpolated == "ORAReplaced")
  }

  it("interpolate should ignore string that contains delimiter without bracket") {

    val original = "ORA$TEST"
    val interpolated = original.interpolate("$"){
      v =>
        "Replaced"
    }
    assert(interpolated == original)
  }

  it("interpolate should allow delimiter to be escaped") {

    val original = "ORA$${TEST}"
    val interpolated = original.interpolate("$"){
      v =>
        "Replaced"
    }
    assert(interpolated == original)
  }

  //  test("1") {
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[String].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[Integer].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[Int].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[java.lang.Double].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[Double].get)
  //
  //    //    val res2: Array[String] = Array("abc", "def").filterByType[String].get
  //    //    println(res2)
  //
  //    println(Set("abc", "def", 3, 4, 2.3).filterByType[String].get)
  //    println(Set("abc", "def", 3, 4, 2.3).filterByType[Integer].get)
  //    println(Set("abc", "def", 3, 4, 2.3).filterByType[Int].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[java.lang.Double].get)
  //    println(Seq("abc", "def", 3, 4, 2.3).filterByType[Double].get)
  //  }
}
