package com.tribbloids.spookystuff.integration.join

import com.tribbloids.spookystuff.actions.Wget
import com.tribbloids.spookystuff.extractors.Extractor

/**
 * Created by peng on 25/10/15.
 */
class LeftWgetJoinIT extends LeftVisitJoinIT {

  override lazy val driverFactories = Seq(
    null
  )

  override def getPage(uri: Extractor[String]) = Wget(uri)
}
