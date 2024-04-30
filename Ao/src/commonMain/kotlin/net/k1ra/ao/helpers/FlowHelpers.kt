package net.k1ra.ao.helpers

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StateFlowClass<T>(private val delegate: StateFlow<T>) : StateFlow<T> by delegate {
    val subscribers: MutableList<Job> = mutableListOf()

    @OptIn(DelicateCoroutinesApi::class)
    fun subscribe(block: (T) -> Unit) : Job {
        val job = GlobalScope.launch(Dispatchers.Main) {
            delegate.collect { value -> block(value) }
        }

        subscribers.add(job)
        return job
    }

    fun removeAllSubscribers() {
        subscribers.forEach {
            if (it.isActive)
                it.cancel()
        }
    }

    fun singletonSubscribe(block: (T) -> Unit) : Job {
        removeAllSubscribers()
        return subscribe(block)
    }
}

fun <T> StateFlow<T>.asStateFlowClass(): StateFlowClass<T> = StateFlowClass(this)