name := "stripe-akka-http"

organization := "com.klubraum"

scalaVersion := "3.3.5"

credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com", "klubraum", System.getenv("GITHUB_TOKEN"))

resolvers ++= Seq("Akka library repository".at("https://repo.akka.io/maven"))

githubOwner := "klubraum"
githubRepository := "stripe-akka-http"

Compile / scalacOptions ++= Seq("-release:17", "-deprecation", "-feature", "-unchecked")
Compile / javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation")

ThisBuild / dynverSeparator := "-"

val AkkaVersion = "2.10.2"
val AkkaHttpVersion = "10.7.0"

val StripeJavaVersion = "28.4.0"

libraryDependencies ++= Seq(
  // Stripe SDK
  "com.stripe" % "stripe-java" % "28.4.0", // As requested

  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-pki" % AkkaVersion,

  // Logging (optional, but good practice)
  "ch.qos.logback" % "logback-classic" % "1.5.18" % Runtime, // Or use akka-slf4j
  "com.typesafe.akka" %% "akka-slf4j" % AkkaVersion % Runtime,

  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)
