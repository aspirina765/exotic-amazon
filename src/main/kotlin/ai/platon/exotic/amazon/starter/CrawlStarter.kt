package ai.platon.exotic.amazon.starter

import ai.platon.exotic.amazon.crawl.boot.CrawlerInitializer
import ai.platon.exotic.amazon.crawl.boot.component.MainCrawler
import ai.platon.exotic.amazon.crawl.boot.component.MainGenerator
import ai.platon.exotic.amazon.crawl.common.ResourceWalker
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.metrics.AppMetrics
import ai.platon.pulsar.context.PulsarContexts
import ai.platon.pulsar.context.support.AbstractPulsarContext
import ai.platon.pulsar.persist.HadoopUtils
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolMonitor
import ai.platon.pulsar.protocol.browser.emulator.BrowserEmulatedFetcher
import ai.platon.scent.ScentSession
import ai.platon.scent.boot.autoconfigure.ScentContextInitializer
import ai.platon.scent.protocol.browser.emulator.context.BrowserPrivacyContextMonitor
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ImportResource

@SpringBootApplication(
    scanBasePackages = [
        "ai.platon.scent.boot.autoconfigure",
        "ai.platon.scent.rest.api",
        "ai.platon.exotic.amazon.crawl.boot",
    ]
)
@ImportResource("classpath:config/app/app-beans/app-context.xml")
class CrawlApplication(
    private val appMetrics: AppMetrics,
    private val driverPoolMonitor: WebDriverPoolMonitor,
    private val privacyContextMonitor: BrowserPrivacyContextMonitor,
    private val mainGenerator: MainGenerator,
    private val mainCrawler: MainCrawler,
    private val session: ScentSession
) {
    private val logger = getLogger(this)

    @Bean
    fun checkConfiguration() {
        val conf = session.unmodifiedConfig

        logger.info("{}", conf)
        val hadoopConf = HadoopUtils.toHadoopConfiguration(conf)
        logger.info("{}", hadoopConf)

        val pulsarContext = PulsarContexts.create() as AbstractPulsarContext
        val fetcher = pulsarContext.getBeanOrNull<BrowserEmulatedFetcher>()
        requireNotNull(fetcher)
    }
}

fun main(args: Array<String>) {
    runApplication<CrawlApplication>(*args) {
        setAdditionalProfiles("rest", "crawler", "amazon")
        addInitializers(ScentContextInitializer(), CrawlerInitializer())
        setRegisterShutdownHook(true)
        setLogStartupInfo(true)
    }
}
