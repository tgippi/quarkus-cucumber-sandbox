Feature: Greetings

  Scenario: Hello Resource
    Given a running quarkus application
    When I ask the hello resource
    Then I should be told "hello"

  Scenario: Greeting Service
    Given a running quarkus application
    When I ask the greeting service
    Then I should be greeted with "hello Nice"