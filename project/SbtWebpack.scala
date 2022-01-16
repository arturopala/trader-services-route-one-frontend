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
        TaskKey[Seq[File]]("webpack", "Run all enabled webpack compilations.")
      val webpackOnly =
        inputKey[Seq[File]]("Run selected compilations for provided ids.")
      val binary = SettingKey[File]("webpackBinary", "The location of webpack binary")
      val nodeModulesPath = TaskKey[File]("webpackNodeModules", "The location of the node_modules.")
      val workingDirectory = SettingKey[File]("webpackWorkingDirectory", "Compilation folder")
      val compilations = SettingKey[Seq[WebpackCompilation]](
        "webpackCompilations",
        "Configurations of the webpack compilations."
      )
    }

    /** Configuration of a single webpack compilation. */
    case class WebpackCompilation(
      /** Compilation id */
      id: String,
      /** A path of the webpack config.js definition relative to the working directory. */
      configFilePath: String,
      /** Condsidered files filter. */
      includeFilter: FileFilter,
      /** Compilation input paths.
        *   - if the path starts with `assets:` it will be resolved in assests source directory.
        *   - if the path starts with `webjar:` it will be resolved in the target/web/webjars/lib directory.
        */
      inputs: Seq[String],
      /** A path of the compilation output file. */
      output: String,
      /** On/Off flag */
      enabled: Boolean = true,
      /** Additional environment properties to add to the webpack execution command line. */
      additionalProperties: Map[String, String] = Map.empty
    )
  }

  import SbtWeb.autoImport._
  import WebKeys._
  import autoImport.WebpackKeys
  import SbtNpm.autoImport.NpmKeys

  final val unscopedWebpackSettings = Seq(
    WebpackKeys.binary := (Assets / sourceDirectory).value / "node_modules" / ".bin" / "webpack",
    WebpackKeys.nodeModulesPath := new File("./node_modules"),
    WebpackKeys.workingDirectory := (Assets / sourceDirectory).value,
    WebpackKeys.webpack := runWebpackCompilations(Seq.empty)
      .dependsOn(Assets / WebKeys.webModules)
      .dependsOn(NpmKeys.npmInstall)
      .value,
    WebpackKeys.webpackOnly := Def.inputTaskDyn {
      val ids = sbt.complete.Parsers.spaceDelimited("<args>").parsed
      runWebpackCompilations(ids)
    }.evaluated,
    WebpackKeys.webpack / excludeFilter := HiddenFileFilter ||
      new FileFilter {
        override def accept(file: File): Boolean = {
          val path = file.getAbsolutePath()
          path.contains("/node_modules/") ||
          path.contains("/build/") ||
          path.contains("/dist/")
        }
      },
    WebpackKeys.webpack / resourceManaged := webTarget.value / "webpack",
    WebpackKeys.webpack / sbt.Keys.skip := false,
    packager.Keys.dist := (packager.Keys.dist dependsOn WebpackKeys.webpack).value,
    managedResourceDirectories += (WebpackKeys.webpack / resourceManaged).value,
    resourceGenerators += WebpackKeys.webpack,
    // Because sbt-webpack might compile sources and output into the same file.
    // Therefore, we need to deduplicate the files by choosing the one in the target directory.
    // Otherwise, the "duplicate mappings" error would occur.
    deduplicators += {
      val targetDir = (WebpackKeys.webpack / resourceManaged).value
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

  final def runWebpackCompilations(acceptedIds: Seq[String]) = Def.task {
    val tag = "[sbt-webpack]"
    val logger: ManagedLogger = (Assets / streams).value.log
    val targetDir: File = (Assets / WebpackKeys.webpack / resourceManaged).value

    val nodeModulesLocation: File = (WebpackKeys.webpack / WebpackKeys.nodeModulesPath).value
    val webpackReporter: Reporter = (Assets / reporter).value
    val webpackBinaryLocation: File = (WebpackKeys.webpack / WebpackKeys.binary).value
    val webpackOutputDirectory: File = (WebpackKeys.webpack / resourceManaged).value
    val assetsLocation: File = (Assets / sourceDirectory).value
    val assetsWebJarsLocation: File = (Assets / webJarsDirectory).value
    val projectRoot: File = baseDirectory.value
    val webpackWorkingDirectory: File = (WebpackKeys.webpack / WebpackKeys.workingDirectory).value

    val webpackCompilations: Seq[autoImport.WebpackCompilation] =
      (WebpackKeys.webpack / WebpackKeys.compilations).value

    val cacheDirectory = (Assets / streams).value.cacheDirectory

    webpackCompilations
      .filter(config => config.enabled && (acceptedIds.isEmpty || acceptedIds.contains(config.id)))
      .flatMap { config =>
        val compilationTag = s"$tag[${config.id}]"

        val webpackEntryFiles: Set[File] =
          config.inputs.map { path =>
            if (path.startsWith("assets:"))
              assetsLocation.toPath.resolve(path.drop(7)).toFile()
            else if (path.startsWith("webjar:"))
              assetsWebJarsLocation.toPath.resolve(path.drop(7)).toFile()
            else webpackWorkingDirectory.toPath().resolve(path).toFile()
          }.toSet

        val webpackConfigFile: File =
          webpackWorkingDirectory.toPath.resolve(config.configFilePath).toFile()

        val webpackOutputFileName: String = config.output

        val sources: Seq[File] =
          ((webpackWorkingDirectory ** (config.includeFilter -- (WebpackKeys.webpack / excludeFilter).value)).get
            .filter(_.isFile) ++ webpackEntryFiles ++ Seq(webpackConfigFile)).distinct

        val globalHash = new String(
          Hash(
            Seq(
              compilationTag,
              readAndClose(webpackConfigFile),
              config.toString
            ).mkString("--")
          )
        )

        val fileHasherIncludingOptions = OpInputHasher[File] { f =>
          OpInputHash.hashString(
            Seq(
              f.getCanonicalPath,
              globalHash
            ).mkString("--")
          )
        }

        val results =
          incremental.syncIncremental(cacheDirectory / compilationTag, sources) { modifiedSources =>
            val startInstant = System.currentTimeMillis

            if (modifiedSources.nonEmpty) {
              logger.info(s"""
                |$compilationTag Detected ${modifiedSources.size} changed files:
                |$compilationTag - ${modifiedSources
                .map(f => f.relativeTo(projectRoot).getOrElse(f).toString())
                .mkString(s"\n$compilationTag - ")}
           """.stripMargin.trim)

              val compiler = new Compiler(
                compilationTag,
                projectRoot,
                webpackBinaryLocation,
                webpackConfigFile,
                webpackEntryFiles,
                webpackOutputFileName,
                webpackOutputDirectory,
                webpackWorkingDirectory,
                assetsLocation,
                assetsWebJarsLocation,
                nodeModulesLocation,
                config.additionalProperties,
                logger
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
              logger.info(s"$compilationTag No changes to re-compile")
              (Map.empty, Seq.empty)
            }

          }(fileHasherIncludingOptions)

        // Return the dependencies
        results._1.toSeq ++ results._2
      }
  }

  final case class CompilationResult(success: Boolean, entries: Seq[CompilationEntry])
  final case class CompilationEntry(inputFile: File, filesRead: Set[File], filesWritten: Set[File])

  object Shell {
    def execute(cmd: Seq[String], cwd: File, envs: (String, String)*): (Int, Seq[String]) = {
      var output = Vector.empty[String]
      val process = Process(cmd, cwd, envs: _*)
      val exitCode = process.!(ProcessLogger(s => output = output.:+(s.trim)))
      (exitCode, output)
    }
  }

  final class Compiler(
    tag: String,
    projectRoot: File,
    binary: File,
    configFile: File,
    entries: Set[File],
    outputFileName: String,
    outputDirectory: File,
    workingDirectory: File,
    assetsLocation: File,
    webjarsLocation: File,
    nodeModulesLocation: File,
    additionalEnvironmentProperties: Map[String, String],
    logger: ManagedLogger
  ) {

    def compile(): CompilationResult = {
      import sbt._

      val entriesEnvs = entries.zipWithIndex.flatMap { case (file, index) =>
        Seq("--env", s"""entry.$index=${file.getAbsolutePath()}""")
      }

      val additionalEnvs = additionalEnvironmentProperties.toSeq
        .flatMap { case (key, value) =>
          Seq("--env", s"$key=$value")
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
        s"""assets.path=${assetsLocation.getAbsolutePath()}""",
        "--env",
        s"""webjars.path=${webjarsLocation.getAbsolutePath()}"""
      ) ++ entriesEnvs ++ additionalEnvs

      logger.info(s"$tag Running command ${AnsiColor.CYAN}${cmd.mkString(" ")}${AnsiColor.RESET}")

      val (exitCode, output) =
        Shell.execute(cmd, workingDirectory, "NODE_PATH" -> nodeModulesLocation.getCanonicalPath)

      val success = exitCode == 0

      if (success) {
        val processedFiles: Seq[File] =
          output
            .filter(s => s.contains("[built]") && (s.startsWith(".") || s.startsWith("|")))
            .map(_.dropWhile(_ == '|').dropWhile(_ == ' ').takeWhile(_ != ' '))
            .sorted
            .map(path => workingDirectory.toPath().resolve(path).toFile)

        logger.info(
          processedFiles
            .map(file => file.relativeTo(projectRoot).getOrElse(file))
            .mkString(
              s"$tag Processed files:\n$tag - ${AnsiColor.GREEN}",
              s"${AnsiColor.RESET}\n$tag - ${AnsiColor.GREEN}",
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
              s"$tag Generated assets:\n$tag - ${AnsiColor.MAGENTA}",
              s"${AnsiColor.RESET}\n$tag - ${AnsiColor.MAGENTA}",
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
          output.map(s => s"$tag $s").mkString("\n")
        )
        CompilationResult(success = false, entries = Seq.empty)
      }
    }
  }

}
