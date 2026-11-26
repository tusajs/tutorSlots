cmake_minimum_required(VERSION 3.20)
project(tutor_slots LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC)
  # /utf-8: test sources contain Cyrillic string literals
  add_compile_options(/utf-8 /W4 /permissive-)
else()
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

add_library(tutor_core src/core.cpp)
target_include_directories(tutor_core PUBLIC include)

add_executable(tutor_slots src/main.cpp)
target_link_libraries(tutor_slots PRIVATE tutor_core)

option(TUTOR_BUILD_TESTS "Build unit tests (Google Test)" ON)
if(TUTOR_BUILD_TESTS)
  find_package(GTest CONFIG QUIET)
  if(NOT GTest_FOUND)
    include(FetchContent)
    FetchContent_Declare(googletest
      URL https://github.com/google/googletest/archive/refs/tags/v1.15.2.zip
      DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    # Use the same C runtime as the rest of the project on MSVC
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)
  endif()

  enable_testing()
  add_executable(core_tests tests/core_test.cpp)
  target_link_libraries(core_tests PRIVATE tutor_core GTest::gtest_main)
  target_compile_definitions(core_tests PRIVATE
    TS_SAMPLES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../samples")
  include(GoogleTest)
  gtest_discover_tests(core_tests)
endif()
