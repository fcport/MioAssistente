cmake_minimum_required(VERSION 3.22.1)
project(mioassistente_whisper C CXX)

# Whisper deve essere sempre ottimizzato, anche nella build "debug", altrimenti è lentissimo
set(CMAKE_BUILD_TYPE Release)
set(OPT "-O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${OPT}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OPT}")
set(CMAKE_C_FLAGS_DEBUG "${OPT}")
set(CMAKE_CXX_FLAGS_DEBUG "${OPT}")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp)

add_library(mioassistente_whisper SHARED jni.c)
target_link_libraries(mioassistente_whisper whisper android log)
# compatibilità con i telefoni con pagine di memoria da 16 KB (Android 15+)
target_link_options(mioassistente_whisper PRIVATE "-Wl,-z,max-page-size=16384")
