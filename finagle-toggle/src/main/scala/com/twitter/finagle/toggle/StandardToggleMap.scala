package com.twitter.finagle.toggle

import com.twitter.finagle.server.ServerInfo
import com.twitter.finagle.stats.StatsReceiver
import com.twitter.logging.Logger
import com.twitter.util.{Return, Throw}
import scala.collection.JavaConverters._

/**
 * A [[ToggleMap]] that is the composition of multiple underlying
 * [[ToggleMap]] implementations in a specific ordering designed
 * to balance control between the operators/service-owners and the library
 * owners.
 *
 * The ordering is as such:
 *  i. A mutable, in-process [[ToggleMap.Mutable]].
 *  i. The `GlobalFlag`-backed [[ToggleMap]], provided via [[ToggleMap.flags]].
 *  i. The service-owner controlled JSON file-based [[ToggleMap]], provided via [[JsonToggleMap]].
 *  i. The dynamically loaded [[ToggleMap]], provided via [[ServiceLoadedToggleMap.apply]].
 *  i. The library-owner controlled JSON file-based [[ToggleMap]], provided via [[JsonToggleMap]].
 *
 * The expectation is that 1, 2, and 3 give service-owners/operators the tools
 * to operate and test [[Toggle toggles]] while library owners would have control
 * over 4 and 5.
 * Flags and in-memory settings allow for rapid testing and overrides
 * while file-based configs are for static configuration owners have settled on.
 *
 * The JSON file-based configuration works via Java resources and must be
 * placed in specific locations in your classpath's resources:
 * `resources/com/twitter/toggles/configs/`. The file-names take the form
 * `$libraryName.json` for the library owner's configuration and
 * `$libraryName-service.json` for the service owner's configuration. As an
 * example, finagle-core would have a
 * `resources/com/twitter/toggles/configs/com.twitter.finagle.json` and service
 * owners can customize toggles via
 * `resources/com/twitter/toggles/configs/com.twitter.finagle-service.json`.
 *
 * The JSON files also support optional environment-specific overrides via
 * files that are examined before the non-environment-specific configs.
 * These environment-specific configs must be placed at
 * `resources/com/twitter/toggles/configs/com.twitter.finagle-$environment.json`
 * or `resources/com/twitter/toggles/configs/com.twitter.finagle-service-$environment.json`
 * where the `environment` from [[ServerInfo.apply()]] is used to determine which
 * one to load.
 */
object StandardToggleMap {

  private[this] val log = Logger.get()

  /**
   * @param libraryName if multiple matching service loaded implementations are
   *                    found, this will fail with an `java.lang.IllegalStateException`.
   *                    The names must be in fully-qualified form to avoid
   *                    collisions, e.g. "com.twitter.finagle". Valid characters are
   *                    `A-Z`, `a-z`, `0-9`, `_`, `-`, `.`.
   * @param statsReceiver used to record the outcomes of Toggles. For general
   *                      usage this should not be scoped so that the metrics
   *                      always end up scoped to "toggles/$libraryName".
   */
  def apply(libraryName: String, statsReceiver: StatsReceiver): ToggleMap =
    apply(
      libraryName,
      statsReceiver,
      ToggleMap.newMutable(),
      ServerInfo())

  /** exposed for testing */
  private[toggle] def apply(
    libraryName: String,
    statsReceiver: StatsReceiver,
    mutable: ToggleMap,
    serverInfo: ServerInfo
  ): ToggleMap = {
    Toggle.validateId(libraryName)

    val svcsJson = loadJsonConfig(libraryName, s"$libraryName-service", serverInfo)
    val libsJson = loadJsonConfig(libraryName, libraryName, serverInfo)

    val stacked = ToggleMap.of(
      mutable,
      ToggleMap.flags,
      svcsJson,
      ServiceLoadedToggleMap(libraryName),
      libsJson
    )
    ToggleMap.observed(stacked, statsReceiver.scope("toggles", libraryName))
  }

  private[this] def loadJsonConfig(
    libraryName: String,
    configName: String,
    serverInfo: ServerInfo
  ): ToggleMap = {
    val withoutEnv = loadJsonConfigWithEnv(libraryName, configName)
    val withEnv = serverInfo.environment match {
      case Some(env) =>
        val e = env.toString.toLowerCase
        loadJsonConfigWithEnv(libraryName, s"$configName-$e")
      case None =>
        NullToggleMap
    }

    // prefer the environment specific config.
    withEnv.orElse(withoutEnv)
  }

  private[this] def loadJsonConfigWithEnv(
    libraryName: String,
    configName: String
  ): ToggleMap = {
    val classLoader = getClass.getClassLoader
    val rscPath = s"com/twitter/toggles/configs/$configName.json"
    val rscs = classLoader.getResources(rscPath).asScala.toSeq
    if (rscs.size > 1) {
      throw new IllegalArgumentException(
        s"Multiple Toggle config resources found for $configName, ${rscs.mkString(",")}")
    } else if (rscs.isEmpty) {
      NullToggleMap
    } else {
      val rsc = rscs.head
      log.debug(s"Toggle config resources found for $configName, using $rsc")
      JsonToggleMap.parse(rsc) match {
        case Throw(t) =>
          throw new IllegalArgumentException(
            s"Failure parsing Toggle config resources for $configName, from $rsc", t)
        case Return(toggleMap) =>
          toggleMap
      }
    }
  }
}
