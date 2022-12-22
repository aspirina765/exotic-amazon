package ai.platon.exotic.amazon.tools.environment

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.crawl.event.WebPageWebDriverEventHandler
import ai.platon.pulsar.crawl.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.session.PulsarSession
import org.slf4j.LoggerFactory
import java.time.Duration

class ChooseLanguageJsEventHandler: WebPageWebDriverEventHandler() {
    var verbose = true

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        val expressions = "document.querySelector(\"input[value=en_US]\").click();\n" +
                "document.querySelector(\"span#icp-btn-save input[type=submit]\").click();"
        expressions.split(";").forEach {
            driver.evaluate(it)
        }
        return null
    }
}

class ChooseCountryJsEventHandler: WebPageWebDriverEventHandler() {
    var verbose = true

    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        // New York City
        val zipcode = listOf("10001", "10001", "10002", "10002", "10003", "10004", "10005", "10006").shuffled().first()
        val expressions = ResourceLoader.readString("sites/amazon/js/choose-district.js")
            .replace("10001", zipcode)
            .split(";\n")
            .filter { it.isNotBlank() }
            .filter { !it.startsWith("// ") }
            .joinToString(";\n")

        expressions.split(";").forEach {
            driver.evaluate(it)
        }

        return null
    }
}

class ChooseCountry(
    val portalUrl: String,
    val loadArguments: String,
    val session: PulsarSession
) {
    private val logger = LoggerFactory.getLogger(ChooseCountry::class.java)
    val chooseLanguageUrl = "https://www.amazon.com/gp/customer-preferences/select-language"

    val options = session.options(loadArguments).apply {
        expires = Duration.ZERO
        refresh = true
    }

    fun choose() {
        // 1. warn up
        val page = session.load(portalUrl, options)

        var document = session.parse(page)
        var text = document.selectFirstOrNull("#glow-ingress-block")?.text() ?: "(unknown)"
        println("Current area: $text")

        // 2. choose language
//        var jsEventHandler: JsEventHandler = ChooseLanguageJsEventHandler()
//        session.load(chooseLanguageUrl, options)
//        session.sessionConfig.removeBean(jsEventHandler)

        // 3. choose district
        val jsEventHandler = ChooseCountryJsEventHandler()
        options.conf.putBean(jsEventHandler)
        session.load(portalUrl, options)
        options.conf.removeBean(jsEventHandler)

        // 4. check the result
        document = session.loadDocument(portalUrl, options)

        text = document.selectFirstOrNull("#nav-tools a span.icp-nav-flag")?.attr("class") ?: "(unknown)"
        logger.info("Current country: $text")

        text = document.selectFirstOrNull("#glow-ingress-block")?.text() ?: "(unknown)"
        logger.info("Current area: $text")
        val path = session.export(document)
        logger.info("Exported to file://$path")
    }
}
