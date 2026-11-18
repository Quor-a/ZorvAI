package com.ai.assistance.quro.core.xposed

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Hook 目标定位的反射工具（纯 JDK 类型，可在 system_server / 厂商 App 进程安全加载）。
 *
 * 一律「找不到就返回 null」，绝不抛到宿主进程；目标签名漂移属于 ROM 升级的正常情况，
 * 由安装报告记为 MISSING，功能降级而不是把 SystemUI / 小布搞崩。
 */
internal object QuroHookSupport {

    fun findClassOrNull(classLoader: ClassLoader, name: String): Class<*>? = runCatching {
        Class.forName(name, false, classLoader)
    }.getOrNull()

    /** 按精确参数类型找方法；兼容父类声明的方法。 */
    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>,
    ): Method? = runCatching {
        val m = clazz.getDeclaredMethod(name, *parameterTypes)
        m.isAccessible = true
        m
    }.getOrElse {
        runCatching {
            val m = clazz.getMethod(name, *parameterTypes)
            m.isAccessible = true
            m
        }.getOrNull()
    }

    /** 参数类型也用类名字符串给定时（system_server 内部类不可直接引用）。 */
    fun findMethodByClassNames(
        classLoader: ClassLoader,
        className: String,
        methodName: String,
        parameterClassNames: List<String>,
    ): Method? {
        val clazz = findClassOrNull(classLoader, className) ?: return null
        val params = parameterClassNames.map { findClassOrNull(classLoader, it) ?: return null }
        return findMethod(clazz, methodName, *params.toTypedArray())
    }

    /** 目标类名不确定时按候选列表依次尝试，返回第一个命中的方法。 */
    fun findFirstMethod(
        classLoader: ClassLoader,
        classNames: List<String>,
        methodName: String,
        parameterClassNames: List<String> = emptyList(),
    ): Pair<Class<*>, Method>? {
        classNames.forEach { cn ->
            val m = findMethodByClassNames(classLoader, cn, methodName, parameterClassNames)
            if (m != null) {
                val c = findClassOrNull(classLoader, cn) ?: return@forEach
                return c to m
            }
        }
        return null
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val f = runCatching {
                val field = current!!.getDeclaredField(name)
                field.isAccessible = true
                field
            }.getOrNull()
            if (f != null) return f
            current = current.superclass
        }
        return null
    }

    fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        val f = findField(target.javaClass, name) ?: return null
        return runCatching { f.get(target) }.getOrNull()
    }

    /** 按谓词筛选声明方法；一律 setAccessible，找不到返回空表。 */
    fun findDeclaredMethods(
        clazz: Class<*>?,
        predicate: (Method) -> Boolean,
    ): List<Method> {
        if (clazz == null) return emptyList()
        return runCatching {
            clazz.declaredMethods.filter(predicate).onEach {
                runCatching { it.isAccessible = true }
            }
        }.getOrDefault(emptyList())
    }

    /** 读取字段（含父类）；字段不存在返回 null。 */
    fun getFieldValue(target: Any?, name: String): Any? {
        if (target == null) return null
        val f = findField(target.javaClass, name) ?: return null
        return runCatching { f.get(target) }.getOrNull()
    }

    /** 调用无参方法；不存在或抛异常都返回 null。 */
    fun invokeNoArgs(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching {
            val m = findMethod(target.javaClass, name)
            m?.invoke(target)
        }.getOrNull()
    }

    fun invokeString(target: Any?, name: String): String =
        (invokeNoArgs(target, name) as? String).orEmpty()

    /**
     * 按名字调用方法，参数按「可赋值」匹配（兼容装箱 / 父类型 / null 实参）。
     * 厂商 setter 的参数类型在同一大版本里会漂（String↔CharSequence），因此不做精确匹配。
     */
    fun invokeByName(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return runCatching {
            val method = findCompatibleMethod(target.javaClass, name, args) ?: return@runCatching null
            method.invoke(target, *args)
        }.getOrNull()
    }

    fun findCompatibleMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val hit = findDeclaredMethods(current) { m ->
                m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.zip(args).all { (type, arg) ->
                        arg == null || wrapPrimitive(type).isAssignableFrom(arg.javaClass)
                    }
            }.firstOrNull()
            if (hit != null) return hit
            current = current.superclass
        }
        return null
    }

    private fun wrapPrimitive(type: Class<*>): Class<*> = when (type) {
        Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
        Int::class.javaPrimitiveType -> Int::class.javaObjectType
        Long::class.javaPrimitiveType -> Long::class.javaObjectType
        Float::class.javaPrimitiveType -> Float::class.javaObjectType
        Double::class.javaPrimitiveType -> Double::class.javaObjectType
        Char::class.javaPrimitiveType -> Char::class.javaObjectType
        else -> type
    }

    /** Kotlin object / 单例：先 INSTANCE 字段，再静态 getInstance()。 */
    fun singletonInstance(classLoader: ClassLoader, className: String): Any? {
        val clazz = findClassOrNull(classLoader, className) ?: return null
        runCatching {
            val field = clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }
            field.get(null)?.let { return it }
        }
        return runCatching {
            findDeclaredMethods(clazz) { m ->
                java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                    m.name == "getInstance" && m.parameterCount == 0
            }.firstOrNull()?.invoke(null)
        }.getOrNull()
    }

    fun isPackageInstalled(context: android.content.Context?, packageName: String): Boolean {
        if (context == null) return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun resolvesActivity(context: android.content.Context?, intent: android.content.Intent): Boolean {
        if (context == null) return false
        return runCatching { context.packageManager.resolveActivity(intent, 0) != null }
            .getOrDefault(false)
    }

    fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")

    /**
     * 写静态字符串字段（如 `Build.MANUFACTURER`）。
     *
     * 为什么不能只 `field.set`：Android 14 起 `Field.mods`/`accessFlags` 已不存在，
     * 旧写法「反射改 accessFlags 去掉 final」会直接抛 NoSuchFieldException；
     * 而 `Field.set` 对 static final 在 Android 17 仍会抛 IllegalAccessException。
     * 这里先试普通写入，失败再用 Unsafe 的 staticFieldBase/Offset + putReferenceVolatile。
     *
     * 写完一定读回校验：读回不一致就返回 false，绝不假装改成功。
     */
    fun setStaticStringField(clazz: Class<*>, name: String, value: String): Boolean {
        val field = runCatching {
            clazz.getDeclaredField(name).apply { isAccessible = true }
        }.getOrNull() ?: return false

        runCatching { field.set(null, value) }
        if (runCatching { field.get(null) == value }.getOrDefault(false)) return true

        return runCatching {
            val unsafe = unsafeInstance() ?: return@runCatching false
            val unsafeClass = unsafe.javaClass
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .apply { isAccessible = true }
                .invoke(unsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .apply { isAccessible = true }
                .invoke(unsafe, field) as Long
            // jdk.internal.misc.Unsafe 叫 putReferenceVolatile；sun.misc.Unsafe 叫 putObjectVolatile
            val put = listOf("putReferenceVolatile", "putObjectVolatile")
                .firstNotNullOfOrNull { methodName ->
                    runCatching {
                        unsafeClass.getDeclaredMethod(
                            methodName,
                            Any::class.java,
                            Long::class.javaPrimitiveType!!,
                            Any::class.java,
                        ).apply { isAccessible = true }
                    }.getOrNull()
                } ?: return@runCatching false
            put.invoke(unsafe, base, offset, value)
            runCatching { field.get(null) == value }.getOrDefault(false)
        }.getOrDefault(false)
    }

    /** 取 Unsafe 单例；两个包名都试，取不到返回 null。 */
    private fun unsafeInstance(): Any? {
        val holder = listOf("jdk.internal.misc.Unsafe", "sun.misc.Unsafe").firstNotNullOfOrNull { className ->
            runCatching {
                val clazz = Class.forName(className)
                clazz.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            }.getOrNull()
        } ?: return null
        return holder
    }

    /**
     * 尝试把方法从 AOT/JIT 优化里摘出来再 Hook。
     *
     * `startOtherServices`、`handleMessage` 这类开机热路径可能已被 AOT 编译，
     * 部分框架实现下不 deopt 会「Hook 返回成功但回调不触发」。失败不影响安装，只记日志。
     */
    fun deoptimize(module: io.github.libxposed.api.XposedModule, executable: java.lang.reflect.Executable): Boolean =
        runCatching { module.deoptimize(executable) }.isSuccess
}
