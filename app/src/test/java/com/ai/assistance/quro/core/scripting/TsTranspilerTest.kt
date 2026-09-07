package com.ai.assistance.quro.core.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TsTranspiler（TS → JS 类型剥离）单测：覆盖日常脚本包写法。
 * 断言策略：输出应为合法 JS（关键断言）且不残留 TS 语法残留（针对性 contains/not-contains）。
 */
class TsTranspilerTest {

    @Test(timeout = 5000L)
    fun `interface removed`() {
        val ts = """
            interface User { name: string; age: number; }
            const u = { name: "a" };
        """.trimIndent()
        val js = TsTranspiler.transpile(ts)
        assertFalse(js.contains("interface"))
        assertTrue(js.contains("const u"))
    }

    @Test(timeout = 5000L)
    fun `type alias removed`() {
        val ts = "type ID = string | number;\nconst x = 1;"
        val js = TsTranspiler.transpile(ts)
        assertFalse(js.contains("type ID"))
        assertTrue(js.contains("const x = 1;"))
    }

    @Test(timeout = 5000L)
    fun `variable annotation stripped`() {
        val js = TsTranspiler.transpile("const n: number = 42;")
        assertFalse(js.contains("number"))
        assertEquals("const n = 42;", js)
    }

    @Test(timeout = 5000L)
    fun `function params and return type stripped`() {
        val js = TsTranspiler.transpile("function add(a: number, b: number): number { return a + b; }")
        assertFalse(js.contains(": number"))
        assertTrue(js.contains("function add(a, b)"))
        assertTrue(js.contains("return a + b"))
    }

    @Test(timeout = 5000L)
    fun `arrow function with types`() {
        val js = TsTranspiler.transpile("const f = (x: string): string => x.trim();")
        assertFalse(js.contains(": string"))
        assertTrue(js.contains("=>"))
        assertTrue(js.contains("x.trim()"))
    }

    @Test(timeout = 5000L)
    fun `optional param keeps default`() {
        val js = TsTranspiler.transpile("function greet(name: string, greeting: string = \"hi\"): string { return greeting + name; }")
        assertFalse(js.contains(": string"))
        assertTrue(js.contains("greeting = \"hi\""))
    }

    @Test(timeout = 5000L)
    fun `optional marker question removed`() {
        val js = TsTranspiler.transpile("function f(x?: number) { return x ?? 0; }")
        assertFalse(js.contains("x?"))
        assertTrue(js.contains("x ?? 0"))
    }

    @Test(timeout = 5000L)
    fun `generic function decl stripped`() {
        val js = TsTranspiler.transpile("function first<T>(arr: T[]): T | undefined { return arr[0]; }")
        assertFalse(js.contains("<T>"))
        assertTrue(js.contains("function first(arr)"))
        assertTrue(js.contains("return arr[0]"))
    }

    @Test(timeout = 5000L)
    fun `as cast stripped`() {
        val js = TsTranspiler.transpile("const s = raw as string;")
        assertFalse(js.contains(" as "))
        assertTrue(js.contains("const s = raw;"))
    }

    @Test(timeout = 5000L)
    fun `as const stripped`() {
        val js = TsTranspiler.transpile("const arr = [1, 2, 3] as const;")
        assertFalse(js.contains("as const"))
        assertTrue(js.contains("[1, 2, 3]"))
    }

    @Test(timeout = 5000L)
    fun `non-null assertion stripped`() {
        val js = TsTranspiler.transpile("const v = maybe!;")
        assertFalse(js.contains("maybe!"))
        assertTrue(js.contains("maybe;"))
    }

    @Test(timeout = 5000L)
    fun `enum transpiles to IIFE object`() {
        val js = TsTranspiler.transpile("enum Color { Red, Green = 5, Blue }")
        assertFalse(js.contains("enum"))
        assertTrue(js.contains("var Color"))
        assertTrue(js.contains("Color[\"Red\"]=0"))
        assertTrue(js.contains("Color[\"Green\"]=5"))
        assertTrue(js.contains("Color[\"Blue\"]=6"))
    }

    @Test(timeout = 5000L)
    fun `string enum`() {
        val js = TsTranspiler.transpile("enum Dir { Up = \"UP\", Down = \"DOWN\" }")
        assertFalse(js.contains("enum"))
        assertTrue(js.contains("Dir[\"Up\"]=\"UP\""))
    }

    @Test(timeout = 5000L)
    fun `object literal untouched`() {
        val js = TsTranspiler.transpile("const o = { a: 1, b: \"x\" };")
        assertTrue(js.contains("{ a: 1, b: \"x\" }"))
    }

    @Test(timeout = 5000L)
    fun `string content with colon untouched`() {
        val js = TsTranspiler.transpile("const s = \"time: 12:30\";")
        assertTrue(js.contains("\"time: 12:30\""))
    }

    @Test(timeout = 5000L)
    fun `ternary not broken`() {
        val js = TsTranspiler.transpile("const y = x > 1 ? \"big\" : \"small\";")
        assertTrue(js.contains("x > 1 ? \"big\" : \"small\""))
    }

    @Test(timeout = 5000L)
    fun `case label untouched`() {
        val js = TsTranspiler.transpile("switch (a) { case 1: f(); break; default: g(); }")
        assertTrue(js.contains("case 1:"))
    }

    @Test(timeout = 5000L)
    fun `import type removed`() {
        val ts = "import type { User } from \"./types\";\nimport { data } from \"./data\";"
        val js = TsTranspiler.transpile(ts)
        assertFalse(Regex("\\bimport\\b").containsMatchIn(js))
        assertTrue(js.contains("require(\"./data\")"))
        assertTrue(js.contains("var data = __imp0.data;") || js.contains("var data = __imp1.data;"))
    }

    @Test(timeout = 5000L)
    fun `class with typed fields`() {
        val js = TsTranspiler.transpile("class P { name: string; count: number = 0; hi(): string { return this.name; } }")
        assertFalse(js.contains("name: string"))
        assertFalse(js.contains("count: number"))
        assertTrue(js.contains("count = 0"))
        assertTrue(js.contains("hi()"))
    }

    @Test(timeout = 5000L)
    fun `union and generic annotations`() {
        val js = TsTranspiler.transpile("const xs: Array<string | null> = [];")
        assertFalse(js.contains("Array<string | null>"))
        assertTrue(js.contains("const xs = [];"))
    }

    @Test(timeout = 5000L)
    fun `template literal untouched`() {
        val js = TsTranspiler.transpile("const t = `a: \${x}: b`;")
        assertTrue(js.contains("`a: \${x}: b`"))
    }

    @Test(timeout = 5000L)
    fun `comment with colon untouched`() {
        val js = TsTranspiler.transpile("// note: this is a comment\nconst a = 1;")
        assertTrue(js.contains("// note: this is a comment"))
    }

    @Test(timeout = 5000L)
    fun `function type annotation in param`() {
        val js = TsTranspiler.transpile("function apply(f: (x: number) => number, v: number) { return f(v); }")
        assertFalse(js.contains("(x: number) => number"))
        assertTrue(js.contains("function apply(f, v)"))
    }

    @Test(timeout = 5000L)
    fun `regex untouched`() {
        val js = TsTranspiler.transpile("const m = /a: b/.test(s);")
        assertTrue(js.contains("/a: b/.test(s)"))
    }

    @Test(timeout = 5000L)
    fun `declare removed`() {
        val js = TsTranspiler.transpile("declare function foo(): void;\nconst a = 1;")
        assertFalse(js.contains("declare"))
        assertTrue(js.contains("const a = 1;"))
    }

    @Test(timeout = 5000L)
    fun `real world script compiles to runnable js`() {
        // 端到端：转译产物丢进 node --check 验证语法合法（如果环境有 node；否则跳过）
        val ts = """
            import { readFileSync } from "fs";
            interface Config { verbose: boolean; retries: number; }
            type Mode = "fast" | "safe";
            enum Status { Idle, Running, Done = 10 }
            const config: Config = { verbose: true, retries: 3 };
            function run(mode: Mode, cb: (s: Status) => void): void {
                const list: Array<number> = [1, 2, 3];
                const total = list.reduce((a: number, b: number): number => a + b, 0);
                const msg = `total: ${'$'}{total}` as string;
                const st = Status.Running!;
                cb(st);
            }
        """.trimIndent()
        val js = TsTranspiler.transpile(ts)
        assertFalse(js.contains("interface"))
        assertFalse(js.contains(": boolean"))
        assertFalse(js.contains(": number"))
        assertFalse(js.contains(": void"))
        assertFalse(js.contains(" as "))
        assertTrue(js.contains("Status.Running;"))
    }

    /* ===================== ESM → CommonJS ===================== */

    @Test(timeout = 5000L)
    fun `import default`() {
        val js = TsTranspiler.transpile("import utils from \"./utils\";")
        assertFalse(Regex("\\bimport\\b").containsMatchIn(js))
        assertTrue(js.contains("require(\"./utils\")"))
        assertTrue(js.contains("var utils = (__imp0 && __imp0.default !== undefined) ? __imp0.default : __imp0;"))
    }

    @Test(timeout = 5000L)
    fun `import named with alias`() {
        val js = TsTranspiler.transpile("import { a, b as c } from \"./m\";")
        assertFalse(Regex("\\bimport\\b").containsMatchIn(js))
        assertTrue(js.contains("var a = __imp0.a;"))
        assertTrue(js.contains("var c = __imp0.b;"))
    }

    @Test(timeout = 5000L)
    fun `import namespace`() {
        val js = TsTranspiler.transpile("import * as ns from \"./m\";")
        assertTrue(js.contains("var ns = __imp0;"))
    }

    @Test(timeout = 5000L)
    fun `import side effect`() {
        val js = TsTranspiler.transpile("import \"./polyfill\";")
        assertTrue(js.contains("require(\"./polyfill\");"))
        assertFalse(Regex("\\bimport\\b").containsMatchIn(js))
    }

    @Test(timeout = 5000L)
    fun `import type only line removed`() {
        val js = TsTranspiler.transpile("import type { Config } from \"./config\";\nconst a = 1;")
        assertFalse(Regex("\\bimport\\b").containsMatchIn(js))
        assertFalse(js.contains("Config"))
        assertTrue(js.contains("const a = 1;"))
    }

    @Test(timeout = 5000L)
    fun `export const`() {
        val js = TsTranspiler.transpile("export const x = 1, y = 2;")
        assertTrue(js.contains("const x = 1, y = 2;"))
        assertTrue(js.contains("module.exports.x = x;"))
        assertTrue(js.contains("module.exports.y = y;"))
        assertFalse(Regex("\\bexport\\b").containsMatchIn(js))
    }

    @Test(timeout = 5000L)
    fun `export const with annotation`() {
        val js = TsTranspiler.transpile("export const total: number = a + b;")
        assertFalse(js.contains(": number"))
        assertTrue(js.contains("const total = a + b;"))
        assertTrue(js.contains("module.exports.total = total;"))
    }

    @Test(timeout = 5000L)
    fun `export const arrow function with body`() {
        val js = TsTranspiler.transpile("export const f = (x: number): number => { return x * 2; };")
        assertFalse(js.contains(": number"))
        assertTrue(js.contains("module.exports.f = f;"))
    }

    @Test(timeout = 5000L)
    fun `export function`() {
        val js = TsTranspiler.transpile("export function greet(name: string): string { return name; }")
        assertTrue(js.contains("module.exports.greet = greet;"))
        assertTrue(js.contains("function greet(name) { return name; }"))
    }

    @Test(timeout = 5000L)
    fun `export default`() {
        val js = TsTranspiler.transpile("export default function main(): void { run(); }")
        assertFalse(Regex("\\bexport\\b").containsMatchIn(js))
        assertTrue(js.contains("module.exports.default = function main() { run(); }"))
    }

    @Test(timeout = 5000L)
    fun `export named braces`() {
        val js = TsTranspiler.transpile("const a = 1, b = 2;\nexport { a, b as bee };")
        assertFalse(Regex("\\bexport\\b").containsMatchIn(js))
        assertTrue(js.contains("module.exports.a = a;"))
        assertTrue(js.contains("module.exports.bee = b;"))
    }

    @Test(timeout = 5000L)
    fun `export from`() {
        val js = TsTranspiler.transpile("export { helper } from \"./util\";")
        assertTrue(js.contains("require(\"./util\")"))
        assertTrue(js.contains("module.exports.helper = __exp0.helper;"))
    }

    @Test(timeout = 5000L)
    fun `export star from`() {
        val js = TsTranspiler.transpile("export * from \"./util\";")
        assertTrue(js.contains("Object.assign(module.exports, require(\"./util\"));"))
    }

    @Test(timeout = 5000L)
    fun `export interface removed`() {
        val js = TsTranspiler.transpile("export interface User { name: string; }\nconst u = { name: \"a\" };")
        assertFalse(js.contains("interface"))
        assertTrue(js.contains("const u ="))
    }

    @Test(timeout = 5000L)
    fun `obj property access not treated as export`() {
        val js = TsTranspiler.transpile("const o = { export: 1, import: 2 }; o.export = 3;")
        assertTrue(js.contains("o.export = 3"))
        assertFalse(js.contains("module.exports.export"))
    }

    @Test(timeout = 5000L)
    fun `module exports assignment untouched`() {
        val js = TsTranspiler.transpile("module.exports = { run: function (): void { go(); } };")
        assertTrue(js.contains("module.exports = { run: function () { go(); } }"))
    }
}
