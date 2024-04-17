package eu.kanade.tachiyomi.extension.all.rokuhentai

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class RHFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        Rokuhentai("all"),
    )
}
