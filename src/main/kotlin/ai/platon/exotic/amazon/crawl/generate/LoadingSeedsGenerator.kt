package ai.platon.exotic.amazon.crawl.generate

import ai.platon.exotic.amazon.crawl.common.ResourceWalker
import ai.platon.exotic.amazon.crawl.core.ClusterTools
import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.collect.CollectorHelper
import ai.platon.pulsar.common.collect.ExternalUrlLoader
import ai.platon.pulsar.common.collect.LocalFileHyperlinkCollector
import ai.platon.pulsar.common.collect.collector.UrlCacheCollector
import ai.platon.pulsar.common.collect.queue.AbstractLoadingQueue
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.persist.WebDb
import ai.platon.pulsar.persist.gora.generated.GWebPage
import ai.platon.scent.common.WebDbLongTimeTask
import ai.platon.scent.crawl.CollectedResidentTask
import ai.platon.scent.crawl.ResidentTask
import ai.platon.scent.crawl.createArgs
import ai.platon.scent.crawl.isSupervised
import java.nio.file.Path

class LoadingSeedsGenerator(
    val tasks: List<ResidentTask>,
    val searchDirectories: List<String>,
    val collectorHelper: CollectorHelper,
    val urlLoader: ExternalUrlLoader,
    val webDb: WebDb
) {
    private val logger = getLogger(this)
    private val taskTime = DateTimes.startOfDay()
    private val taskId = taskTime.toString()
    private val isDev = ClusterTools.isDevInstance()

    val collectedTasks = mutableListOf<CollectedResidentTask>()

    private val fields = listOf(
        GWebPage.Field.PREV_FETCH_TIME,
        GWebPage.Field.PREV_CRAWL_TIME1,
    ).map { it.getName() }.toTypedArray()

    fun isSupervisor(task: ResidentTask) = isDev || task.isSupervised()

    fun generate(refresh: Boolean): List<UrlCacheCollector> {
        collectLoadingTasks()

        logger.info("Collected tasks: " + collectedTasks.joinToString { it.task.name })

        val collectors = mutableListOf<UrlCacheCollector>()
        collectedTasks.forEach { task ->
            collectors.add(createCollector(task, refresh))
            // reduce database load
            sleepSeconds(15)
        }

        tasks.filter { it !in collectedTasks.map { it.task } }
            .filter { !collectorHelper.contains(it.name) }
            .mapTo(collectors) { createFetchCacheCollector(it, urlLoader) }

        return collectors
    }

    fun createCollector(collectedTask: CollectedResidentTask, refresh: Boolean): UrlCacheCollector {
        val task = collectedTask.task

        // If the collector is still alive, remove it
        removeOldCollectors(task)

        val collector = createFetchCacheCollector(task, urlLoader)

        if (refresh) {
            collector.deepClear()
        }

        if (collector.externalSize > 0) {
            logger.info(
                "There are still {} tasks in collector {}, do not generate",
                collector.estimatedSize, collector.name
            )

            return collector
        }

        val links = collectedTask.hyperlinks
        if (links.isEmpty()) {
            return collector
        }

        val readyQueue = collector.urlCache.nonReentrantQueue as AbstractLoadingQueue
        logger.info("Checking {} links for task <{}> in database", links.size, task.name)
        val (fetchedUrls, time) = measureTimedValueJvm {
            WebDbLongTimeTask(webDb, task.name).getAll(links, fields)
                .filter { it.prevFetchTime >= task.startTime() }
                .mapTo(HashSet()) { it.url }
        }

        links.asSequence().filterNot { it.url in fetchedUrls }
            .onEach { it.args = task.createArgs(taskId, taskTime).toString() }
            .toCollection(readyQueue)

        logger.info(
            "Generated {}/{} {} tasks with collector {} in {}, with {} ones removed(fetched)",
            readyQueue.size, readyQueue.externalSize, task.name, collector.name, time, fetchedUrls.size
        )

        return collector
    }

    private fun getRelevantCollectors(task: ResidentTask): List<UrlCacheCollector> {
        return collectorHelper.feeder.collectors
            .filter { task.name in it.name }
            .filterIsInstance<UrlCacheCollector>()
    }

    private fun removeOldCollectors(task: ResidentTask): List<UrlCacheCollector> {
        // If the collector is still alive, remove it
        return getRelevantCollectors(task).onEach {
            collectorHelper.removeAllLike(task.name)
        }
    }

    private fun createFetchCacheCollector(task: ResidentTask, urlLoader: ExternalUrlLoader): UrlCacheCollector {
        val priority = task.priority.value
        collectorHelper.remove(task.name)

        logger.info("Creating collector for {}", task.name)

        return collectorHelper.addUrlPoolCollector(task.name, priority, urlLoader).also {
            it.deadTime = task.deadTime()
            it.labels.add(task.name)
        }
    }

    private fun collectLoadingTasks() {
        searchDirectories.forEach {
            ResourceWalker().walk(it, 5) { path ->
                println("Path - $path")
                loadTasksIfMatch(path)
            }
        }
    }

    private fun loadTasksIfMatch(path: Path) {
        val collectedResidentTasks = tasks
            .filter { isSupervisor(it) }
            .filter { !it.fileName.isNullOrBlank() }
            .filter { path.endsWith(it.fileName!!) }
            .map { CollectedResidentTask(it, collectHyperlinks(path)) }

        collectedTasks.addAll(collectedResidentTasks)
    }

    private fun collectHyperlinks(path: Path): Set<Hyperlink> {
        return collectHyperlinksTo(path, mutableSetOf())
    }

    private fun collectHyperlinksTo(path: Path, hyperlinks: MutableSet<Hyperlink>): Set<Hyperlink> {
        val collector = LocalFileHyperlinkCollector(path)

        val links = if (isDev) collector.hyperlinks.take(100) else collector.hyperlinks

        val isCluster = ClusterTools.isCluster()
        when {
            isCluster -> collector.hyperlinks
            else -> collector.hyperlinks.take(100)
        }

        val message = if (isDev) " (dev mode)" else ""
        logger.info("Loaded {} links$message | {}", links.size, path)

        hyperlinks.addAll(links)

        return hyperlinks
    }
}
