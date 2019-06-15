package helloscala.common.util

import org.scalatest.FunSuite
import org.scalatest.MustMatchers

class StringUtilsTest extends FunSuite with MustMatchers {

  test("randomString") {
    (StringUtils.randomString(8) must have).length(8)
    (StringUtils.randomString(8) must have).length(8)
    (StringUtils.randomString(8) must have).length(8)
    println(StringUtils.randomExtString(8))
  }

}
