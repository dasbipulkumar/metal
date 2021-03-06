import com.typesafe.sbt.site.util.SiteHelpers
import com.typesafe.sbt.SbtGhPages.GhPagesKeys._
import sbtunidoc.Plugin.UnidocKeys._

val scalaCheckVersion = "1.13.2"
val scalaTestVersion = "3.0.0"
val spireVersion = "0.11.0"

// custom keys used by sbt-site

lazy val tutorialSubDirName = settingKey[String]("Website tutorial directory")
lazy val apiSubDirName = settingKey[String]("Unidoc API directory")

// projects

lazy val metal = (project in file("."))
  .settings(moduleName := "metal")
  .settings(metalSettings)
  .settings(noPublishSettings)
  .aggregate(core, library, docs)
  .dependsOn(core, library)

lazy val docs = (project in file("docs"))
  .settings(moduleName := "metal-docs")
  .settings(metalSettings)
  .settings(noPublishSettings)
  .settings(tutConfig)
  .settings(unidocConfig)
  .settings(siteConfig)
  .dependsOn(core, library)

lazy val core = (project in file("core"))
  .settings(moduleName := "metal-core")
  .settings(metalSettings)
  .settings(crossVersionSharedSources)

lazy val library = (project in file("library"))
  .settings(moduleName := "metal-library")
  .settings(metalSettings)
  .settings(scalaTestSettings)
  .settings(libraryDependencies += "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test")
  .settings(crossVersionSharedSources)
  .dependsOn(core)

lazy val metalSettings = buildSettings ++ commonSettings ++ publishSettings

lazy val buildSettings = Seq(
  name := "metal",
  organization := "org.scala-metal",
  scalaVersion := "2.11.8",
  crossScalaVersions := Seq("2.10.6", "2.11.8")
)

lazy val commonSettings = Seq(
  apiURL := Some(url("https://denisrosset.github.io/metal/latest/api")),
  scmInfo := Some(ScmInfo(url("https://github.com/denisrosset/metal"), "scm:git:git@github.com:denisrosset/metal.git")),
  scalacOptions in (Compile, doc) := (scalacOptions in (Compile, doc)).value.filter(_ != "-Xfatal-warnings"),
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  scalacOptions ++= commonScalacOptions.diff(Seq(
    "-Xfatal-warnings",
    "-language:existentials",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard"
  )),
  resolvers ++= Seq(
    "bintray/non" at "http://dl.bintray.com/non/maven",
    Resolver.sonatypeRepo("snapshots")
  ),
  libraryDependencies += "org.spire-math" %% "spire" % spireVersion
) ++ scalaMacroDependencies ++ warnUnusedImport ++ selectiveOptimize ++ doctestConfig

lazy val publishSettings = Seq(
  homepage := Some(url("http://denisrosset.github.io/metal")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  bintrayRepository := "maven",
  publishArtifact in Test := false
)

// do not optimize on Scala 2.10 because of optimizer bug, see SI-3882
lazy val selectiveOptimize = 
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        Seq()
      case Some((2, n)) if n >= 11 =>
        Seq("-optimize")
    }
  }

lazy val scalaTestSettings = Seq(
  libraryDependencies += "org.scalatest" %% "scalatest" % scalaTestVersion % "test"
)

///////////////////////////////////////////////////////////////////////////////////////////////////
// Base documentation settings, taken from https://github.com/denisrosset/fizz

lazy val siteConfig = ghpages.settings ++ Seq(
  siteMappings ++= Seq(
    file("CONTRIBUTING.md") -> "contributing.md"
  ),
  ghpagesNoJekyll := false,
  git.remoteRepo := "git@github.com:denisrosset/metal.git",
  includeFilter in makeSite := "*.html" | "*.css" | "*.png" | "*.jpg" | "*.gif" | "*.js" | "*.swf" | "*.yml" | "*.md"
)

lazy val doctestConfig = doctestSettings ++ Seq(
  doctestTestFramework := DoctestTestFramework.ScalaTest, // opinion: we default to Scalatest
  // the following two lines specify an explicit Scalatest version and tell sbt-doctest to
  // avoid importing new dependencies
  libraryDependencies ++= Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion % "test",
    "org.scalacheck" %% "scalacheck" % scalaCheckVersion % "test"
  ),
  doctestWithDependencies := false
)

lazy val unidocConfig = unidocSettings ++ Seq(
  apiSubDirName := "latest/api",
  // sbt-site will use the generated documentation
  addMappingsToSiteDir(mappings in (ScalaUnidoc, packageDoc), apiSubDirName),
  // projects to include
  unidocProjectFilter in (ScalaUnidoc, unidoc) := inProjects(core, library),
  // enable automatic linking to the external Scaladoc of our own managed dependencies
  autoAPIMappings := true,
  scalacOptions in (ScalaUnidoc, unidoc) ++= Seq(
    // we want warnings to be fatal (on broken links for example)
    "-Xfatal-warnings", 
    // link to source code, yes that's an euro symbol
    "-doc-source-url", scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    // generate type hierarchy diagrams, runs graphviz
    "-diagrams"
  )
)

lazy val tutConfig = tutSettings ++ Seq(
  tutorialSubDirName := "_tut",
  addMappingsToSiteDir(tut, tutorialSubDirName),
  tutScalacOptions ~= (_.filterNot(Set("-Ywarn-unused-import", "-Ywarn-dead-code")))
)

////////////////////////////////////////////////////////////////////////////////////////////////////
// Base Build Settings - Should not need to edit below this line. 
//
// Taken from the common keys across various Typelevel projects, see e.g. cats

lazy val noPublishSettings = Seq(
  publish := (),
  publishLocal := (),
  publishArtifact := false
)

lazy val crossVersionSharedSources: Seq[Setting[_]] =
  Seq(Compile, Test).map { sc =>
    (unmanagedSourceDirectories in sc) ++= {
      (unmanagedSourceDirectories in sc ).value.map {
        dir:File => new File(dir.getPath + "_" + scalaBinaryVersion.value)
      }
    }
  }

lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:experimental.macros",
  "-unchecked",
  "-Xfatal-warnings",
  "-Xlint",
  "-Yinline-warnings",
  "-Yno-adapted-args",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture"
)

lazy val scalaMacroDependencies: Seq[Setting[_]] = Seq(
  libraryDependencies += "org.scala-lang" % "scala-reflect" % scalaVersion.value % "provided",
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      // if scala 2.11+ is used, quasiquotes are merged into scala-reflect
      case Some((2, scalaMajor)) if scalaMajor >= 11 => Seq()
      // in Scala 2.10, quasiquotes are provided by macro paradise
      case Some((2, 10)) =>
        Seq(
          compilerPlugin("org.scalamacros" % "paradise" % "2.0.1" cross CrossVersion.full),
              "org.scalamacros" %% "quasiquotes" % "2.0.1" cross CrossVersion.binary
        )
    }
  }
)

lazy val warnUnusedImport = Seq(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 10)) =>
        Seq()
      case Some((2, n)) if n >= 11 =>
        Seq("-Ywarn-unused-import")
    }
  },
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _)},
  scalacOptions in (Test, console) <<= (scalacOptions in (Compile, console))
)
