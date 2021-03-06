package eu.semberal.dbstress

import java.io.File
import java.lang.Math.{max, min}

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.slf4j.LazyLogging
import eu.semberal.dbstress.config.ConfigParser.parseConfigurationYaml
import scopt.OptionParser

object Main extends LazyLogging {

  case class CmdLineArguments(configFile: File = null, outputDir: File = null, maxDbWorkerThreads: Option[Int] = None)

  def main(args: Array[String]): Unit = {
    val parser = new OptionParser[CmdLineArguments]("dbstress") {
      private val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("unknown")

      head("dbstress", version, "Database performance and stress testing tool")

      opt[File]('c', "config").valueName("CONFIG_FILE").text("Path to the configuration YAML file").required().action { (x, c) =>
        c.copy(configFile = x)
      }.validate {
        case x if !x.exists() => failure(s"File '$x' does not exist")
        case x if !x.isFile => failure(s"'$x' is not a file")
        case x if !x.canRead => failure(s"File '$x' is not readable")
        case _ => success
      }

      opt[File]('o', "output").valueName("OUTPUT_DIR").text("Output directory").required().action { (x, c) =>
        c.copy(outputDir = x)
      }.validate {
        case x if !x.exists() => failure(s"Directory '$x' does not exist")
        case x if !x.isDirectory => failure(s"'$x' is not a directory")
        case x if !x.canWrite => failure(s"Directory '$x' is not writeable")
        case _ => success
      }

      opt[Int]("max-db-threads").valueName("N").text("Max db worker threads").action { (x, c) =>
        c.copy(maxDbWorkerThreads = Some(x))
      }.validate {
        case x if x <= 0 => failure("Max database worker threads count must be a positive integer")
        case _ => success
      }

      version("version").text("Show application version")
      help("help").text("Show help")

      override def showUsageOnError: Boolean = true
    }

    parser.parse(args, CmdLineArguments()) match {
      case Some(CmdLineArguments(configFile, outputDir, maxDbWorkersThreads)) => parseConfigurationYaml(configFile) match {
        case Right(sc) =>
          val minConn: Int = sc.units.map(_.parallelConnections).sum
          val maxConn: Int = {
            val defaultMax = minConn * 3 / 2
            maxDbWorkersThreads.map(x => min(max(minConn, x), defaultMax)).getOrElse(defaultMax)
          }

          logger.info(s"Database worker threads count: default=$minConn max=$maxConn")
          val dbDispatcherConfig = ConfigFactory.parseString( s"""
          akka {
            dispatchers {
              db-dispatcher {
                thread-pool-executor {
                  core-pool-size-min = $minConn
                  core-pool-size-max = $maxConn
                }
              }
            }
          }
          """)
          val system = ActorSystem("dbstressMaster", dbDispatcherConfig.withFallback(ConfigFactory.load()))
          new Orchestrator(outputDir).run(sc, system)
        case Left(msg) =>
          System.err.println(s"Configuration error: $msg")
          System.exit(2) // exit status 2 when configuration parsing error has occurred
      }
      case None => System.exit(1) // exit status 1 when command line arguments were incorrect
    }
  }
}