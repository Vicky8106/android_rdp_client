cmake_minimum_required(VERSION 3.22.1)

project(freerdp-android LANGUAGES C CXX)

# Source and prebuilt library paths
set(CPP_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp")
set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}")

# Include search directories
include_directories(
    ${CPP_SRC_DIR}
    ${CPP_SRC_DIR}/include
)

# Locate mandatory Android NDK system libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

# Define native C bridge source files
set(FREERDP_ANDROID_SRCS
    ${CPP_SRC_DIR}/android_freerdp.c
    ${CPP_SRC_DIR}/android_jni_callback.c
)

# Optional prebuilt FreeRDP core libraries (dynamically linked if present)
set(OPTIONAL_PREBUILTS
    crypto
    ssl
    winpr3
    freerdp3
    freerdp-client3
)

set(LINK_LIBS
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

foreach(LIB_NAME ${OPTIONAL_PREBUILTS})
    set(LIB_PATH "${JNILIBS_DIR}/lib${LIB_NAME}.so")
    if(EXISTS "${LIB_PATH}")
        message(STATUS "Found prebuilt library: ${LIB_PATH}")
        add_library(${LIB_NAME} SHARED IMPORTED)
        set_target_properties(${LIB_NAME} PROPERTIES IMPORTED_LOCATION "${LIB_PATH}")
        list(APPEND LINK_LIBS ${LIB_NAME})
    endif()
endforeach()

# Compile the JNI bridge shared library
add_library(freerdp-android SHARED
    ${FREERDP_ANDROID_SRCS}
)

# Link system libraries and resolved prebuilt libraries
target_link_libraries(freerdp-android PRIVATE
    ${LINK_LIBS}
)
