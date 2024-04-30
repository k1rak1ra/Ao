package net.k1ra.ao.model

import kotlinx.coroutines.Job
import net.k1ra.ao.helpers.StateFlowClass
import net.k1ra.ao.model.characteristicState.CharacteristicState

class Characteristic(
    val uuid: String,
    val charId: String,
    val readable: Boolean,
    val observable: Boolean,
    val writable: Boolean,
    val writableWithoutResponse: Boolean,

    private var observeStateFlow: StateFlowClass<ByteArray?>? = null,

    private val read: (suspend () -> CharacteristicState),
    private val observe: (suspend () -> StateFlowClass<ByteArray?>),
    private val cancelObserve: (suspend () -> Unit),
    private val write: (suspend (ByteArray) -> CharacteristicState),
    private val writeNoResp: (suspend (ByteArray) -> CharacteristicState),
) {
    suspend fun read(): CharacteristicState = read.invoke()

    suspend fun observe(block: (ByteArray?) -> Unit): Job {
        var sf = observeStateFlow

        if (sf == null) {
            sf = observe.invoke()
            observeStateFlow = sf
        }

        return sf.subscribe(block)
    }

    suspend fun singletonObserve(block: (ByteArray?) -> Unit): Job {
        var sf = observeStateFlow

        if (sf == null) {
            sf = observe.invoke()
            observeStateFlow = sf
        }

        return sf.singletonSubscribe(block)
    }

    suspend fun cancelObserve() {
        observeStateFlow?.removeAllSubscribers()
        observeStateFlow = null
        cancelObserve.invoke()
    }

    suspend fun write(data: ByteArray): CharacteristicState = write.invoke(data)
    suspend fun writeNoResp(data: ByteArray): CharacteristicState = writeNoResp.invoke(data)
}