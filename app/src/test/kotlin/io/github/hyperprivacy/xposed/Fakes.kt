package io.github.hyperprivacy.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

/** A hooking framework that only records what was hooked, how, and with which hooker. */
internal class FakeXposed {
    class Installed(val executable: Executable, val mode: ExceptionMode?, val hooker: Hooker)

    val installed = mutableListOf<Installed>()

    val api: XposedInterface = proxy(XposedInterface::class.java) { name, args ->
        if (name == "hook") builder(args?.get(0) as Executable) else null
    }

    private fun builder(executable: Executable): HookBuilder {
        var mode: ExceptionMode? = null
        lateinit var self: HookBuilder
        self = proxy(HookBuilder::class.java) { name, args ->
            when (name) {
                "setExceptionMode" -> self.also { mode = args?.get(0) as ExceptionMode }
                "intercept" -> null.also { installed += Installed(executable, mode, args?.get(0) as Hooker) }
                else -> self
            }
        }
        return self
    }

    private fun <T> proxy(type: Class<T>, answer: (String, Array<out Any?>?) -> Any?): T {
        val instance = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            answer(method.name, args)
        }
        return checkNotNull(type.cast(instance))
    }
}

/** One intercepted call: [original] stands for the hooked method; [proceeds] counts how often it ran. */
internal class FakeChain(
    private val executable: Executable,
    private val args: List<Any?> = emptyList(),
    private val thisObject: Any? = null,
    private val original: (Array<Any?>) -> Any? = { null },
) : Chain {
    var proceeds = 0
        private set

    override fun getExecutable(): Executable = executable

    override fun getThisObject(): Any? = thisObject

    override fun getArgs(): List<Any?> = args

    override fun getArg(index: Int): Any? = args[index]

    override fun proceed(): Any? = proceed(args.toTypedArray())

    override fun proceed(args: Array<Any?>): Any? {
        proceeds++
        return original(args)
    }

    override fun proceedWith(thisObject: Any): Any? = proceed()

    override fun proceedWith(thisObject: Any, args: Array<Any?>): Any? = proceed(args)
}
