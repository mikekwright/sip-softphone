package com.github.mikekwright.sip.softphone.factories

import com.github.mikekwright.sip.softphone.SipStackDetails
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar

class JainFactoriesTest extends FlatSpec with Matchers with MockitoSugar with BeforeAndAfterEach {
  var mockSipStackDetails: SipStackDetails = _
  var testModel: JainFactories = _

  override def beforeEach(): Unit = {
    super.beforeEach()

    mockSipStackDetails = mock[SipStackDetails]
    testModel = new JainFactories(mockSipStackDetails)
  }

  "headerFactory" should "create a header factory when called" in {
    testModel.headerFactory() should not be null
  }


}