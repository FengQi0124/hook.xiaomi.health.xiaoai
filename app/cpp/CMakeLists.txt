cmake_minimum_required(VERSION 3.22)
project(xiaoai CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(xiaoai_native SHARED
    native_hook.cpp
)

find_library(log-lib log)
target_link_libraries(xiaoai_native ${log-lib})

# 内联汇编跳板 + 极少数 ARM64 指令模板：必须 -O2 / -fPIC
target_compile_options(xiaoai_native PRIVATE
    -O2 -fPIC -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers
    -fno-rtti -fno-exceptions -ffunction-sections -fdata-sections
)

# link时 strip 掉未使用段，控制 so 体积在 ~30KB 以内
target_link_options(xiaoai_native PRIVATE
    -Wl,--gc-sections -Wl,-s -Wl,--exclude-libs,ALL
)

# 仅支持 LSPosed 的最小组合：arm64-v8a + x86_64
set_target_properties(xiaoai_native PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/../src/main/jniLibs/${ANDROID_ABI}
)
