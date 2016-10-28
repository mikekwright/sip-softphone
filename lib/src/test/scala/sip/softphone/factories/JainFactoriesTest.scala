package sip.softphone.factories

import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar
import sip.softphone.SipStackDetails

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