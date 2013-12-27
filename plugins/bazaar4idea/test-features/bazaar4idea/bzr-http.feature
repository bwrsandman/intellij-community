@remote
Feature: Support remote operation over HTTP.
  As a happy Bazaar integration user
  I want to be able to work with Bazaar remotes via HTTP protocol.

  Scenario: Branch from HTTP url containing username
    When I branch http://bzruser@deb6-vm7-bzr/projectA.bzr
    Then I should be asked for the password
    When I provide password 'bzrpassword'
    Then repository should be branched to projectA

  Scenario: Branch from HTTP url without username
    When I clone http://deb6-vm7-bzr/projectA.bzr
    Then I should be asked for the username
    When I provide username 'bzruser'
    Then I should be asked for the password
    When I provide password 'bzrpassword'
    Then repository should be branched to projectA

  Scenario: Branch from HTTP url containing username, provide incorrect password
    When I clone http://bzruser@deb6-vm7-bzr/projectA.bzr
    Then I should be asked for the password
    When I provide password 'incorrect'
    Then repository should not be cloned to projectA
    And error notification is shown 'Clone failed'
       """
       fatal: Authentication failed
       """
