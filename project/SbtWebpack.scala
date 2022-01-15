import java.io.PrintWriter
import java.nio.file.Paths

import com.typesafe.sbt.web._
import com.typesafe.sbt.web.incremental._
import com.typesafe.sbt.packager
import sbt.Keys._
import sbt._
import xsbti.{Position, Problem, Severity}

import scala.io.Source
import sbt.internal.util.ManagedLogger
import xsbti.Reporter
import scala.sys.process.ProcessLogger
import scala.io.AnsiColor

import java.io.{File, FileOutputStream, PrintWriter}
import java.nio.file.{Files, Path}
import sbt.internal.util.ManagedLogger
import scala.io.Source
import play.sbt.PlayRunHook
import sbt.internal.util.ConsoleLogger
import scala.util.Properties
import scala.sys.process.Process

/** Runs `webpack` command in assets. Project's build has to define `entries` and `outputFileName` settings. Supports
  * `skip` setting.
  */
object SbtWebpack extends AutoPlugin {

  override def requires = SbtWeb

  override def trigger = AllRequirements

  object autoImport {
    object WebpackKeys {
      val webpack =
        TaskKey[Seq[File]]("webpack", "Run webpack to compile .js and .ts sources into a single .js")
      val webpackCss =
        TaskKey[Seq[File]]("webpackCss", "Run webpack to compile .sass and .scss sources into a single .css")

      val binary = SettingKey[File]("webpackBinary", "The location of webpack binary")
      val configFile = SettingKey[File]("webpackConfigFile", "The location of webpack.config.js")
      val nodeModulesPath = TaskKey[File]("webpackNodeModules", "The location of the node_modules.")
      val sourceDirs = SettingKey[Seq[File]]("webpackSourceDirs", "The directories that contains source files.")
      val entries = SettingKey[Seq[String]](
        "webpackEntries",
        "The entry point pseudo-paths. If the path starts with `assets:` it will be resolved in assests source directory, if the path starts with `webjar:` it will be resolved in the webjars/lib target directory."
      )
      val outputFileName =
        SettingKey[String]("webpackOutputFileName", "The name of the webpack output file.")
      val outputPath =
        SettingKey[String]("webpackOutputPath", "The path of the webpack output inside the target folder.")
    }
  }

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport.WebpackKeys
  import SbtNpm.autoImport.NpmKeys

  final val unscopedWebpackSettings = Seq(
    WebpackKeys.binary := (Assets / sourceDirectory).value / "node_modules" / ".bin" / "webpack",
    WebpackKeys.sourceDirs := Seq((Assets / sourceDirectory).value),
    WebpackKeys.nodeModulesPath := new File("./node_modules"),
    // JS compilation config
    WebpackKeys.webpack := compileUsingWebpackTask(WebpackKeys.webpack, "sbt-webpack-js")
      .dependsOn(Assets / WebKeys.webModules)
      .dependsOn(NpmKeys.npmInstall)
      .value,
    WebpackKeys.webpack / excludeFilter := HiddenFileFilter ||
      new FileFilter {
        override def accept(file: File): Boolean = {
          val path = file.getAbsolutePath()
          path.contains("/node_modules/") ||
          path.contains("/build/") ||
          path.contains("/dist/")
        }
      },
    WebpackKeys.webpack / includeFilter := "*.js" || "*.ts",
    WebpackKeys.webpack / resourceManaged := webTarget.value / "webpack-js",
    WebpackKeys.webpack / sbt.Keys.skip := false,
    packager.Keys.dist := (packager.Keys.dist dependsOn WebpackKeys.webpack).value,
    managedResourceDirectories += (WebpackKeys.webpack / resourceManaged).value,
    resourceGenerators += WebpackKeys.webpack,
    // CSS compilation config
    WebpackKeys.webpackCss := compileUsingWebpackTask(WebpackKeys.webpackCss, "sbt-webpack-css")
      .dependsOn(Assets / WebKeys.webModules)
      .dependsOn(NpmKeys.npmInstall)
      .value,
    WebpackKeys.webpackCss / excludeFilter := HiddenFileFilter ||
      new FileFilter {
        override def accept(file: File): Boolean = {
          val path = file.getAbsolutePath()
          path.contains("/node_modules/") ||
          path.contains("/build/") ||
          path.contains("/dist/")
        }
      },
    WebpackKeys.webpackCss / includeFilter := "*.sass" || "*.scss",
    WebpackKeys.webpackCss / resourceManaged := webTarget.value / "webpack-css",
    WebpackKeys.webpackCss / sbt.Keys.skip := false,
    packager.Keys.dist := (packager.Keys.dist dependsOn WebpackKeys.webpackCss).value,
    managedResourceDirectories += (WebpackKeys.webpackCss / resourceManaged).value,
    resourceGenerators += WebpackKeys.webpackCss,
    // Because sbt-webpack might compile sources and output into the same file.
    // Therefore, we need to deduplicate the files by choosing the one in the target directory.
    // Otherwise, the "duplicate mappings" error would occur.
    deduplicators += {
      val targetDir = (WebpackKeys.webpack / resourceManaged).value
      val targetDirAbsolutePath = targetDir.getAbsolutePath

      { files: Seq[File] => files.find(_.getAbsolutePath.startsWith(targetDirAbsolutePath)) }
    },
    deduplicators += {
      val targetDir = (WebpackKeys.webpackCss / resourceManaged).value
      val targetDirAbsolutePath = targetDir.getAbsolutePath

      { files: Seq[File] => files.find(_.getAbsolutePath.startsWith(targetDirAbsolutePath)) }
    }
  )

  override def projectSettings: Seq[Setting[_]] =
    inConfig(Assets)(unscopedWebpackSettings)

  final def readAndClose(file: File): String =
    if (file.exists() && file.canRead()) {
      val s = Source.fromFile(file)
      try s.mkString
      finally s.close()
    } else ""

  def compileUsingWebpackTask(taskKey: TaskKey[Seq[File]], taskId: String) = Def.task {

    val skip = (taskKey / sbt.Keys.skip).value
    val logger: ManagedLogger = (Assets / streams).value.log
    val baseDir: File = (Assets / sourceDirectory).value
    val targetDir: File = (Assets / taskKey / resourceManaged).value

    val nodeModulesLocation: File = (taskKey / WebpackKeys.nodeModulesPath).value
    val webpackSourceDirs: Seq[File] = (taskKey / WebpackKeys.sourceDirs).value
    val webpackReporter: Reporter = (Assets / reporter).value
    val webpackBinaryLocation: File = (taskKey / WebpackKeys.binary).value
    val webpackConfigFileLocation: File = (taskKey / WebpackKeys.configFile).value
    val webpackEntries: Seq[String] = (taskKey / WebpackKeys.entries).value
    val webpackOutputPath: String = (taskKey / WebpackKeys.outputPath).value
    val webpackOutputFileName: String = (taskKey / WebpackKeys.outputFileName).value
    val webpackTargetDir: File = (taskKey / resourceManaged).value
    val assetsWebJarsLocation: File = (Assets / webJarsDirectory).value
    val projectRoot: File = baseDirectory.value

    val webpackEntryFiles: Set[File] =
      webpackEntries.map { path =>
        if (path.startsWith("assets:"))
          baseDir.toPath.resolve(path.drop(7)).toFile()
        else if (path.startsWith("webjar:"))
          assetsWebJarsLocation.toPath.resolve(path.drop(7)).toFile()
        else projectRoot.toPath().resolve(path).toFile()
      }.toSet

    val sources: Seq[File] = (webpackSourceDirs
      .flatMap { sourceDir =>
        (sourceDir ** ((taskKey / includeFilter).value -- (taskKey / excludeFilter).value)).get
      }
      .filter(_.isFile) ++ webpackEntryFiles ++ Seq(webpackConfigFileLocation)).distinct

    val globalHash = new String(
      Hash(
        Seq(
          readAndClose(webpackConfigFileLocation)
        ).mkString("--")
      )
    )

    val fileHasherIncludingOptions = OpInputHasher[File] { f =>
      OpInputHash.hashString(
        Seq(
          taskId,
          f.getCanonicalPath,
          baseDir.getAbsolutePath,
          globalHash
        ).mkString("--")
      )
    }

    val results = incremental.syncIncremental((Assets / streams).value.cacheDirectory / taskId, sources) {
      modifiedSources =>
        val startInstant = System.currentTimeMillis

        if (!skip && modifiedSources.nonEmpty) {
          logger.info(s"""
            |[$taskId] Detected ${modifiedSources.size} changed files:
            |[$taskId] - ${modifiedSources
            .map(f => f.relativeTo(projectRoot).getOrElse(f).toString())
            .mkString(s"\n[$taskId] - ")}
           """.stripMargin.trim)

          val compiler = new Compiler(
            taskId,
            projectRoot,
            webpackBinaryLocation,
            webpackConfigFileLocation,
            webpackEntryFiles,
            webpackOutputFileName,
            webpackTargetDir / webpackOutputPath,
            assetsWebJarsLocation,
            baseDir,
            targetDir,
            logger,
            nodeModulesLocation
          )

          // Compile all modified sources at once
          val result: CompilationResult = compiler.compile()

          // Report compilation problems
          CompileProblems.report(
            reporter = webpackReporter,
            problems =
              if (!result.success)
                Seq(new Problem {
                  override def category() = ""

                  override def severity() = Severity.Error

                  override def message() = ""

                  override def position() =
                    new Position {
                      override def line() = java.util.Optional.empty()

                      override def lineContent() = ""

                      override def offset() = java.util.Optional.empty()

                      override def pointer() = java.util.Optional.empty()

                      override def pointerSpace() = java.util.Optional.empty()

                      override def sourcePath() = java.util.Optional.empty()

                      override def sourceFile() = java.util.Optional.empty()
                    }
                })
              else Seq.empty
          )

          val opResults = result.entries
            .filter { entry =>
              // Webpack might generate extra files from extra input files. We can't track those input files.
              modifiedSources.exists(f => f.getCanonicalPath == entry.inputFile.getCanonicalPath)
            }
            .map { entry =>
              entry.inputFile -> OpSuccess(entry.filesRead, entry.filesWritten)
            }
            .toMap

          // The below is important for excluding unrelated files in the next recompilation.
          val resultInputFilePaths = result.entries.map(_.inputFile.getCanonicalPath)
          val unrelatedOpResults = modifiedSources
            .filterNot(file => resultInputFilePaths.contains(file.getCanonicalPath))
            .map { file =>
              file -> OpSuccess(Set(file), Set.empty)
            }
            .toMap

          val createdFiles = result.entries.flatMap(_.filesWritten).distinct
          val endInstant = System.currentTimeMillis

          (opResults ++ unrelatedOpResults, createdFiles)
        } else {
          if (skip)
            logger.info(s"[$taskId] Skiping webpack")
          else
            logger.info(s"[$taskId] No changes to re-compile")
          (Map.empty, Seq.empty)
        }

    }(fileHasherIncludingOptions)

    // Return the dependencies
    (results._1 ++ results._2.toSet).toSeq

  }

  case class CompilationResult(success: Boolean, entries: Seq[CompilationEntry])
  case class CompilationEntry(inputFile: File, filesRead: Set[File], filesWritten: Set[File])

  object Shell {
    def execute(cmd: Seq[String], cwd: File, envs: (String, String)*): (Int, Seq[String]) = {
      var output = Vector.empty[String]
      val process = Process(cmd, cwd, envs: _*)
      val exitCode = process.!(ProcessLogger(s => output = output.:+(s.trim)))
      (exitCode, output)
    }
  }

  class Compiler(
    taskId: String,
    projectRoot: File,
    binary: File,
    configFile: File,
    entries: Set[File],
    outputFileName: String,
    outputDirectory: File,
    webjarsDirectory: File,
    baseDir: File,
    targetDir: File,
    logger: ManagedLogger,
    nodeModules: File
  ) {

    def getFile(path: String): File =
      if (path.startsWith("/"))
        new File(path)
      else
        targetDir.toPath.resolve(path).toFile.getCanonicalFile

    def compile(): CompilationResult = {
      import sbt._

      val entriesEnvs = entries.zipWithIndex.flatMap { case (file, index) =>
        Seq("--env", s"""entry.$index=${file.getAbsolutePath()}""")
      }

      val cmd = Seq(
        s"""${binary.getCanonicalPath}""",
        "--config",
        s"""${configFile.getAbsolutePath()}""",
        "--env",
        s"""output.path=${outputDirectory.getAbsolutePath()}""",
        "--env",
        s"""output.filename=$outputFileName""",
        "--env",
        s"""webjars.path=${webjarsDirectory.getAbsolutePath()}"""
      ) ++ entriesEnvs

      logger.info(s"[$taskId] Running command ${AnsiColor.CYAN}${cmd.mkString(" ")}${AnsiColor.RESET}")

      val (exitCode, output) =
        Shell.execute(cmd, baseDir, "NODE_PATH" -> nodeModules.getCanonicalPath)

      val success = exitCode == 0

      if (success) {
        val processedFiles: Seq[File] =
          output
            .filter(s => s.contains("[built]") && (s.startsWith(".") || s.startsWith("|")))
            .map(_.dropWhile(_ == '|').dropWhile(_ == ' ').takeWhile(_ != ' '))
            .sorted
            .map(path => baseDir.toPath().resolve(path).toFile)

        logger.info(
          processedFiles
            .map(file => file.relativeTo(projectRoot).getOrElse(file))
            .mkString(
              s"[$taskId] Processed files:\n[$taskId] - ${AnsiColor.GREEN}",
              s"${AnsiColor.RESET}\n[$taskId] - ${AnsiColor.GREEN}",
              s"${AnsiColor.RESET}\n"
            )
        )

        val generatedAssets: Seq[File] =
          output
            .filter(s => s.startsWith("asset ") || s.startsWith("sourceMap "))
            .map(_.dropWhile(_ != ' ').drop(1).takeWhile(_ != ' '))
            .sorted
            .map(path => outputDirectory.toPath().resolve(path).toFile)

        logger.info(
          generatedAssets
            .map(file => file.relativeTo(projectRoot).getOrElse(file))
            .mkString(
              s"[$taskId] Generated assets:\n[$taskId] - ${AnsiColor.MAGENTA}",
              s"${AnsiColor.RESET}\n[$taskId] - ${AnsiColor.MAGENTA}",
              s"${AnsiColor.RESET}\n"
            )
        )

        CompilationResult(
          success = true,
          entries = Seq(
            CompilationEntry(
              inputFile = configFile,
              filesRead = processedFiles.toSet,
              filesWritten = generatedAssets.toSet
            )
          )
        )
      } else {
        logger.error(
          output.map(s => s"[$taskId] $s").mkString("\n")
        )
        CompilationResult(success = false, entries = Seq.empty)
      }
    }
  }

}
