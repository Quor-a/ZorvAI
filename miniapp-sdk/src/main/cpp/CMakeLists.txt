# Self-developed JavaScript engine (no third-party JS engine, no JSR/V8/QuickJS).
cmake_minimum_required(VERSION 3.22.1)
project(miniappjs CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

set(ENGINE_SOURCES
        js_lexer.cpp
        js_parser.cpp
        js_runtime.cpp
        js_interpreter.cpp
        js_builtins.cpp
        js_engine.cpp
)

# ---- engine core (static, reused by tests) ----
add_library(miniapp_engine STATIC ${ENGINE_SOURCES})
target_include_directories(miniapp_engine PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(ANDROID)
    target_compile_options(miniapp_engine PRIVATE -O2 -Wall)
else()
    target_compile_options(miniapp_engine PRIVATE -O2 -Wall)
endif()

# ---- JNI bridge (shared library loaded by JsNative) ----
add_library(miniappjs SHARED jni_bridge.cpp)
target_link_libraries(miniappjs miniapp_engine ${ANDROID_STL_LIB} log)
target_include_directories(miniappjs PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ---- native unit tests (desktop build only) ----
if(NOT ANDROID)
    enable_testing()
    add_executable(js_engine_test tests/js_engine_test.cpp)
    target_link_libraries(js_engine_test miniapp_engine)
    add_test(NAME js_engine_test COMMAND js_engine_test)

    # Runs the bundled demo pages (assets/miniprograms/**) through the engine.
    add_executable(page_runtime_test tests/page_runtime_test.cpp)
    target_link_libraries(page_runtime_test miniapp_engine)
    add_test(NAME page_runtime_test
             COMMAND page_runtime_test ${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/assets/miniprograms)
endif()
