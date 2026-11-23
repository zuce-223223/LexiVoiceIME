cmake_minimum_required(VERSION 3.22)
project(hccr_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ncnn Android 预编译包（由 CI 下载解压到 third_party/ncnn-android/<abi>/）
set(NCNN_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/ncnn-android/${ANDROID_ABI})
if(NOT EXISTS ${NCNN_ROOT})
    message(FATAL_ERROR "ncnn-android not found at ${NCNN_ROOT}")
endif()
set(ncnn_DIR ${NCNN_ROOT}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

set(HCCR_CORE_CPP ${CMAKE_CURRENT_LIST_DIR}/../cpp)

add_library(hccr_jni SHARED
    ${CMAKE_CURRENT_LIST_DIR}/hccr_jni.cc
    ${HCCR_CORE_CPP}/preprocess.c
)

target_include_directories(hccr_jni PRIVATE ${HCCR_CORE_CPP})
target_link_libraries(hccr_jni PRIVATE ncnn android log)
