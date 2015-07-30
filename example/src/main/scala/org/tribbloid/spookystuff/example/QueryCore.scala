package org.tribbloid.spookystuff.example

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.tribbloid.spookystuff.SpookyContext

trait QueryCore extends LocalSpookyCore {

  override def finalize(): Unit = {
    sc.stop()
  }

  def doMain(spooky: SpookyContext): Any

  final def main(args: Array[String]) {

    val spooky = getSpooky(args)
    val result = doMain(spooky)

    val array = result match {
      case schemaRdd: DataFrame =>
        val array = schemaRdd.rdd.persist().takeSample(withReplacement = false, num = 100)
        schemaRdd.printSchema()
        println(schemaRdd.schema.fieldNames.mkString("[","\t","]"))
        array
      case rdd: RDD[_] =>
        rdd.persist().takeSample(withReplacement = false, num = 100)
    }

    array.foreach(row => println(row))
    println("-------------------returned "+array.length+" rows------------------")
    println(s"------------------fetched ${spooky.metrics.pagesFetched.value} pages-----------------")
    println(s"------------------${spooky.metrics.pagesFetchedFromCache.value} pages from web cache-----------------")

    //    rdd.saveAsTextFile("file://"+System.getProperty("user.home")+"/spooky-local/result"+s"/$appName-${System.currentTimeMillis()}.json")
  }
}