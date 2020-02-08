package org.acme.hellocucumber;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.response.Response;
import org.acme.quickstart.GreetingService;
import org.junit.jupiter.api.Assertions;

import javax.inject.Inject;
import javax.inject.Singleton;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

@Singleton
public class StepDefinitions {
    Response response;
    String greeting;

    @Inject
    GreetingService greetingService;

    @Given("a running quarkus application")
    public void today_is_Sunday() {
        // Write code here that turns the phrase above into concrete actions
    }

    @When("I ask the hello resource")
    public void i_ask_whether_it_s_Friday_yet() {
        response = given()
                .when().get(System.getProperty("test.url") + "/hello");
    }

    @Then("I should be told {string}")
    public void i_should_be_told(String string) {
        response.then()
                .statusCode(200)
                .body(is(string));
    }

    @When("I ask the greeting service")
    public void i_ask_greeting_service() {
        greeting = greetingService.greeting("Nice");
    }

    @Then("I should be greeted with {string}")
    public void i_should_be_greeted(String string) {
        Assertions.assertEquals(string, greeting);
    }
}
