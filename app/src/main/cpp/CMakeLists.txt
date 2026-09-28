cmake_minimum_required(VERSION 3.22.1)

project("omnidocs")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -DNDEBUG")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -DNDEBUG -std=c++17")

# Create native USearch library for HNSW vector search
add_library(usearch-android SHARED
    usearch_jni.cpp
)

find_library(log-lib log)

target_link_libraries(usearch-android
    ${log-lib}
)

target_include_directories(usearch-android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/usearch
)

