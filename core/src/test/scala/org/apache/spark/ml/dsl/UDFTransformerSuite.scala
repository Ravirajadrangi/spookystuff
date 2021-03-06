package org.apache.spark.ml.dsl

import com.tribbloids.spookystuff.testutils.{TestHelper, FunSpecx}
import org.apache.spark.ml.feature.Tokenizer
import org.apache.spark.sql.functions._
import org.scalatest.FunSuite


case class User(
               name: String,
               age: Int
               )

class UDFTransformerSuite extends FunSpecx {

  val df1 = TestHelper.TestSQL.createDataFrame(Seq(
    User("Reza$", 25),
    User("Holden$", 25)
  ))

  val tokenizer = new Tokenizer().setInputCol("name").setOutputCol("name_token")
  val stemming = udf {
    v: Seq[String] => v.map(_.stripSuffix("$"))
  }
  val arch = UDFTransformer().setUDF(stemming).setInputCols(Array("name_token")).setOutputCol("name_stemmed")
  val src = tokenizer.transform(df1)

  it("transformer has consistent schema") {
    val end = arch.transform(src)
    val endSchema = end.schema
    val endSchema2 = arch.transformSchema(src.schema)
    assert(endSchema.toString() == endSchema2.toString())
  }

  it("transformer can add new column") {
    val end = arch.transform(src)
    end.collect().mkString("\n").shouldBe()
//    end.show(false)
  }
}
