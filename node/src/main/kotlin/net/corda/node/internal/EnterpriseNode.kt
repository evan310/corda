package net.corda.node.internal

import com.codahale.metrics.MetricFilter
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.graphite.GraphiteReporter
import com.codahale.metrics.graphite.PickledGraphite
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import net.corda.core.crypto.newSecureRandom
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.Emoji
import net.corda.core.internal.concurrent.thenMatch
import net.corda.core.node.NodeInfo
import net.corda.core.utilities.loggerFor
import net.corda.node.VersionInfo
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.config.RelayConfiguration
import net.corda.node.services.statemachine.*
import net.corda.node.utilities.EnterpriseNamedCacheFactory
import net.corda.node.utilities.profiling.getTracingConfig
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import java.io.IOException
import java.net.Inet6Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

open class EnterpriseNode(configuration: NodeConfiguration,
                          versionInfo: VersionInfo,
                          initialiseSerialization: Boolean = true,
                          flowManager: FlowManager = NodeFlowManager(configuration.flowOverrides)
) : Node(configuration, versionInfo, initialiseSerialization, flowManager, cacheFactoryPrototype = EnterpriseNamedCacheFactory(configuration.enterpriseConfiguration.getTracingConfig())) {
    companion object {
        private val logger by lazy { loggerFor<EnterpriseNode>() }

        private fun defaultGraphitePrefix(legalName: CordaX500Name): String {
            return (legalName.organisation + "_" + legalName.locality + "_" + legalName.country + "_" + Inet6Address.getLocalHost().hostAddress)
        }

        fun getGraphitePrefix(configuration: NodeConfiguration): String {
            val customPrefix = configuration.graphiteOptions!!.prefix
            // Create a graphite prefix stripping all non-allowed characteres
            val graphiteName = (customPrefix ?: defaultGraphitePrefix(configuration.myLegalName))
                    .trim().replace(Regex("[^0-9a-zA-Z_]"), "_")
            if (customPrefix != null && graphiteName != customPrefix) {
                logger.warn("Invalid graphite prefix ${customPrefix} specified in config - got mangled to ${graphiteName}. Only letters, numbers and underscores are allowed")
            }
            return graphiteName
        }
    }

    class NodeCli : NodeStartupCli() {
        override val startup = Startup()
    }

    class Startup : NodeStartup() {
        override fun preNetworkRegistration(conf: NodeConfiguration) {
            super.preNetworkRegistration(conf)
            conf.relay?.let { connectToRelay(it, conf.p2pAddress.port) }
        }

        override fun drawBanner(versionInfo: VersionInfo) {
            // This line makes sure ANSI escapes work on Windows, where they aren't supported out of the box.
            AnsiConsole.systemInstall()

            val license = """
*************************************************************************************************************************************
*  All rights reserved.                                                                                                             *
*  This software is proprietary to and embodies the confidential technology of R3 LLC ("R3").                                       *
*  Possession, use, duplication or dissemination of the software is authorized only pursuant to a valid written license from R3.    *
*  IF YOU DO NOT HAVE A VALID WRITTEN LICENSE WITH R3, DO NOT USE THIS SOFTWARE.                                                    *
*************************************************************************************************************************************
"""
            val logo = """
R   ______               __       B  _____ _   _ _____ _____ ____  ____  ____  ___ ____  _____
R  / ____/     _________/ /___ _  B | ____| \ | |_   _| ____|  _ \|  _ \|  _ \|_ _/ ___|| ____|
R / /     __  / ___/ __  / __ `/  B |  _| |  \| | | | |  _| | |_) | |_) | |_) || |\___ \|  _|
R/ /___  /_/ / /  / /_/ / /_/ /   B | |___| |\  | | | | |___|  _ <|  __/|  _ < | | ___) | |___
R\____/     /_/   \__,_/\__,_/    B |_____|_| \_| |_| |_____|_| \_\_|   |_| \_\___|____/|_____|
D""".trimStart()

            val version = generateVersionString(versionInfo) + System.lineSeparator()
            val tipPrefix = if (Emoji.hasEmojiTerminal) "${Emoji.CODE_LIGHTBULB}  " else "Tip: "

            println(license)

            if (Ansi.isEnabled()) {
                drawColourful(logo, version, tipPrefix)
            } else {
                drawPlain(logo, version, tipPrefix)
            }
        }

        private fun generateVersionString(versionInfo: VersionInfo): String {
            val versionString = "--- ${versionInfo.vendor} ${versionInfo.releaseVersion} (${versionInfo.revision.take(7)}) ---"
            // Make sure the version string is padded to be the same length as the logo
            val paddingLength = Math.max(93 - versionString.length, 0)
            return versionString + "-".repeat(paddingLength)
        }

        private fun drawColourful(logo: String, version: String, tipPrefix: String) {
            val colourLogo = addColours(logo)
            val banner = colourLogo +
                    System.lineSeparator() +
                    Ansi.ansi().fgBrightDefault().bold().a(version).reset() +
                    System.lineSeparator() +
                    tipPrefix + Ansi.ansi().bold().a(tip).reset() +
                    System.lineSeparator()
            println(banner)
        }

        private fun addColours(logo: String): String {
            // Replace the R and B letters with their colour code escapes to make the banner prettier.
            val red = Ansi.ansi().fgBrightRed().toString()
            val blue = Ansi.ansi().fgBrightBlue().toString()
            val default = Ansi.ansi().reset().toString()
            return logo.replace("R", red).replace("B", blue).replace("D", default)
        }

        private fun drawPlain(logo: String, version: String, tipPrefix: String) {
            println(logo.replace("R", "").replace("B", "").replace("D", ""))
            println(version)
            println(tipPrefix + tip)
        }

        private val tip: String
            get() {
                val tips = javaClass.getResourceAsStream("tips.txt").bufferedReader().use { it.readLines() }
                return tips[(Math.random() * tips.size).toInt()]
            }

        override fun createNode(conf: NodeConfiguration, versionInfo: VersionInfo) = EnterpriseNode(conf, versionInfo)

        private fun connectToRelay(config: RelayConfiguration, localBrokerPort: Int) {
            with(config) {
                val jsh = JSch().apply {
                    val noPassphrase = byteArrayOf()
                    addIdentity(privateKeyFile.toString(), publicKeyFile.toString(), noPassphrase)
                }

                val session = jsh.getSession(username, relayHost, sshPort).apply {
                    // We don't check the host fingerprints because they may change often, and we are only relaying
                    // data encrypted under TLS anyway: the relay box is NOT considered trusted. A compromised relay
                    // box could observe packet sizes and timings, but the sort of attackers who might be able to
                    // use such information have mostly compromised the network backbone already anyway. So this makes
                    // setup more robust without changing security much.
                    setConfig("StrictHostKeyChecking", "no")
                }

                try {
                    logger.info("Connecting to a relay at $relayHost")
                    session.connect()
                } catch (e: JSchException) {
                    throw IOException("Unable to establish a SSH connection: $username@$relayHost", e)
                }
                try {
                    val localhost = "127.0.0.1"
                    logger.info("Forwarding ports: $relayHost:$remoteInboundPort -> $localhost:$localBrokerPort")
                    session.setPortForwardingR(remoteInboundPort, localhost, localBrokerPort)
                } catch (e: JSchException) {
                    throw IOException("Unable to set up port forwarding - is SSH on the remote host configured correctly? " +
                            "(port forwarding is not enabled by default)", e)
                }
            }

            logger.info("Relay setup successfully!")
        }
    }

    private fun registerOptionalMetricsReporter(configuration: NodeConfiguration, metrics: MetricRegistry) {
        if (configuration.graphiteOptions != null) {
            nodeReadyFuture.thenMatch({
                serverThread.execute {
                    GraphiteReporter.forRegistry(metrics)
                            .prefixedWith(getGraphitePrefix(configuration))
                            .convertDurationsTo(TimeUnit.MILLISECONDS)
                            .convertRatesTo(TimeUnit.SECONDS)
                            .filter(MetricFilter.ALL)
                            .build(PickledGraphite(configuration.graphiteOptions!!.server, configuration.graphiteOptions!!.port))
                            .start(configuration.graphiteOptions!!.sampleInvervallSeconds, TimeUnit.SECONDS)
                }
            }, { th ->
                log.error("Unexpected exception", th)
            })
        }
    }

    override fun start(): NodeInfo {
        val info = super.start()
        registerOptionalMetricsReporter(configuration, services.monitoringService.metrics)
        return info
    }

    private fun makeStateMachineExecutorService(): ExecutorService {
        log.info("Multi-threaded state machine manager with ${configuration.enterpriseConfiguration.tuning.flowThreadPoolSize} threads.")
        return MultiThreadedStateMachineExecutor(metricRegistry, configuration.enterpriseConfiguration.tuning.flowThreadPoolSize)
    }

    override fun makeStateMachineManager(): StateMachineManager {
        if (configuration.enterpriseConfiguration.useMultiThreadedSMM) {
            val executor = makeStateMachineExecutorService()
            runOnStop += { executor.shutdown() }
            return MultiThreadedStateMachineManager(
                    services,
                    checkpointStorage,
                    executor,
                    database,
                    newSecureRandom(),
                    busyNodeLatch,
                    cordappLoader.appClassLoader
            )
        } else {
            log.info("Single-threaded state machine manager with 1 thread.")
            return super.makeStateMachineManager()
        }
    }

    override fun makeFlowLogicRefFactoryImpl(): FlowLogicRefFactoryImpl = EnterpriseFlowLogicRefFactoryImpl(cordappLoader.appClassLoader, cacheFactory)
}
