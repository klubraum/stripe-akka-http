package com.klubraum.stripe

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import com.stripe.Stripe
import com.stripe.exception.AuthenticationException
import com.stripe.model.{ Account, Customer }
import com.stripe.net.{ ApiResource, LiveStripeResponseGetter }
import com.stripe.param.{ CustomerCreateParams, CustomerListParams }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.language.postfixOps

class AkkaHttpClientSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  // --- Test Setup ---
  private implicit var system: ActorSystem[Nothing] = _
  private var akkaHttpClient: AkkaHttpClient = _
  private val stripeTestApiKey: Option[String] = sys.env.get("STRIPE_TEST_SECRET_KEY")
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  override def beforeAll(): Unit = {
    // --- SAFETY CHECK ---
    assume(stripeTestApiKey.isDefined, "STRIPE_TEST_SECRET_KEY environment variable not set, skipping tests.")
    assume(
      stripeTestApiKey.get.startsWith("sk_test_"),
      "STRIPE_TEST_SECRET_KEY does not look like a test key (must start with sk_test_), skipping tests for safety."
    )
    // --- END SAFETY CHECK ---

    logger.debug(s"Found Stripe Test Key: ${stripeTestApiKey.get.take(11)}...") // Don't log the full key

    Stripe.apiKey = stripeTestApiKey.get // Set the test API key

    system = ActorSystem(Behaviors.empty, "AkkaHttpClientTestSystem")
    akkaHttpClient = new AkkaHttpClient()(system) // Using implicit system

    // Store original client and set the custom one
    val srg = new LiveStripeResponseGetter(akkaHttpClient)
    ApiResource.setGlobalResponseGetter(srg)

    logger.debug(s"Test setup complete. Using HttpClient: ${akkaHttpClient.getClass.getName}")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    if (system != null) {

      // Terminate ActorSystem
      system.terminate()
      Await.ready(system.whenTerminated, 15.seconds) // Wait longer for cleanup
      logger.debug("ActorSystem terminated.")
    }
    super.afterAll()
  }

  // --- Test Cases ---

  "AkkaHttpClient" should {

    "successfully make a simple GET request (retrieve Account)" in {
      // Requires account.read permission for the API key
      val account = Account.retrieve()
      logger.debug(s"Retrieved account ID: ${account.getId}")
      account shouldNot be(null)
      account.getId should startWith("acct_")
    }

    "successfully make a GET request with parameters (list Customers)" in {
      // Requires customer.read permission
      val params = CustomerListParams.builder().setLimit(1L).build()
      val customers = Customer.list(params)
      logger.debug(s"Retrieved ${customers.getData.size()} customer(s). HasMore: ${customers.getHasMore}")
      customers shouldNot be(null)
      customers.getData shouldNot be(null)
      // We don't know if there are customers, just that the call succeeded
    }

    "successfully make a POST request (create Customer)" in {
      // Requires customer.write permission
      val customerEmail = s"test-${System.currentTimeMillis()}@example.com"

      logger.debug(s"Creating customer with email: $customerEmail")
      val params =
        CustomerCreateParams.builder().setEmail(customerEmail).setDescription("AkkaHttpClient Test Customer").build()
      val customer = Customer.create(params)
      logger.debug(s"Created customer ID: ${customer.getId}, Email: ${customer.getEmail}")
      customer shouldNot be(null)
      logger.debug(s"Attempted cleanup for customer ${customer.getId}")
      customer.delete()
      customer.getId should startWith("cus_")
      customer.getEmail shouldBe customerEmail
      customer.getDescription shouldBe "AkkaHttpClient Test Customer"
    }

    "handle Stripe Authentication Error (401)" in {
      val originalKey = Stripe.apiKey
      Stripe.apiKey = "sk_test_invalid_key" // Use an invalid key
      logger.debug("Testing with invalid API key...")

      val exception = intercept[AuthenticationException] {
        Account.retrieve() // Attempt any call
      }

      logger.debug(s"Caught expected exception: ${exception.getClass.getSimpleName}")
      exception shouldNot be(null)
      exception.getStatusCode shouldBe 401

      Stripe.apiKey = originalKey // Restore correct key
      logger.debug("Restored correct API key.")
    }

    // Note: Testing connection errors (ApiConnectionException) is harder without
    // mocking the HTTP layer or manipulating the network. These tests focus on
    // successful connections and standard API error handling.

  }
}
