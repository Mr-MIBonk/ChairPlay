package io.github.numberonebot.chairplay.data

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages

/** What the CarPlayDataBus tests share: a bus on a clock of their own, the events it raised, and frames to feed it. */
abstract class CarPlayDataBusFixture {
    protected var now = 0L
    protected val bus = CarPlayDataBus(nanoTime = { now })
    protected val events = ArrayList<CarPlayDataEvent>()

    init {
        bus.addListener { events += it }
    }

    protected fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = bus.accept(Iap2Messages.buildRaw(id, block))

    protected inline fun <reified E : CarPlayDataEvent> last(): E = events.filterIsInstance<E>().last()
}
