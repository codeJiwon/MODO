package com.modo.domain.engine

import java.util.UUID

fun interface IdGenerator {
    fun next(prefix: String): String
}

/** 실제 서버/앱용: 충돌 가능성이 낮은 무작위 id. */
object RandomIdGenerator : IdGenerator {
    override fun next(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(12)}"
}

/** 데모/테스트용: 항상 같은 순서로 같은 id를 만들어 시나리오를 재현할 수 있게 한다. */
class SequentialIdGenerator : IdGenerator {
    private val counters = mutableMapOf<String, Int>()

    @Synchronized
    override fun next(prefix: String): String {
        val n = (counters[prefix] ?: 0) + 1
        counters[prefix] = n
        return "$prefix-$n"
    }

    @Synchronized
    fun reset() = counters.clear()
}
