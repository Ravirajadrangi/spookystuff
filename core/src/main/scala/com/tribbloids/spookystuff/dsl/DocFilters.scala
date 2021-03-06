package com.tribbloids.spookystuff.dsl

import com.tribbloids.spookystuff.actions.DocFilter
import com.tribbloids.spookystuff.doc.Doc
import com.tribbloids.spookystuff.session.Session

//TODO: support chaining & extends ExpressionLike/TreeNode
sealed trait AbstractDocFilter extends DocFilter {

  def assertStatusCode(page: Doc){
    page.httpStatus.foreach {
      v =>
        assert(v.getStatusCode.toString.startsWith("2"), v.toString)
    }
  }
}

object DocFilters {

  case object Bypass extends AbstractDocFilter {

    override def apply(result: Doc, session: Session): Doc = {
      result
    }
  }

  case object AcceptStatusCode2XX extends AbstractDocFilter {

    override def apply(result: Doc, session: Session): Doc = {
      assertStatusCode(result)
      result
    }
  }

  case object MustHaveTitle extends AbstractDocFilter {

    override def apply(result: Doc, session: Session): Doc = {
      assertStatusCode(result)
      if (result.mimeType.contains("html")){
        assert(
          result.root.\("html").\("title").text.getOrElse("").nonEmpty,
          s"Html Page @ ${result.uri} has no title" +
            result.root.code.map {
              code =>
                ":\n" + code.slice(0,500)
            }
              .getOrElse("")
        )
      }
      result
    }
  }
}