name := """transfer-watchers"""
organization := "coop.rchain"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.0"

libraryDependencies += guice
libraryDependencies += ws
libraryDependencies += "com.typesafe.play" %% "play-slick" % "4.0.2"
libraryDependencies += "com.typesafe.play" %% "play-slick-evolutions" % "4.0.2"
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "4.0.3" % Test
libraryDependencies += "org.postgresql" % "postgresql" % "42.2.8"

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "coop.rchain.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "coop.rchain.binders._"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-Xfatal-warnings"
)